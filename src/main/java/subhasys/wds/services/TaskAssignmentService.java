/**
 * 
 */
package subhasys.wds.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import subhasys.wds.dao.AgentDao;
import subhasys.wds.dao.TaskAssignmentDao;
import subhasys.wds.domain.Agent;
import subhasys.wds.domain.Skill;
import subhasys.wds.domain.Task;
import subhasys.wds.enums.TaskPriority;
import subhasys.wds.enums.TaskStatus;
import subhasys.wds.utils.TaskAssignmentUtil;

/**
 * @author subhasis
 *
 */
public class TaskAssignmentService {

	private final TaskAssignmentDao taskAssignmentDao;
	private final AgentDao agentDao;
	public static final String COLUMN_REQUESTED_TASKS = "tasks";
	public static final String COLUMN_TASK_ID = "taskId";

	/**
	 * @param taskAssignmentDao
	 */
	public TaskAssignmentService(TaskAssignmentDao taskAssignmentDao, AgentDao agentDao) {
		this.taskAssignmentDao = taskAssignmentDao;
		this.agentDao = agentDao;
	}

	public boolean isValidatTaskRequest(RoutingContext wdsApiContext) {
		Task requestedTask = TaskAssignmentUtil.getDomainObjectFromRequest(wdsApiContext);
		
		return Objects.nonNull(requestedTask) && Objects.nonNull(requestedTask.getTaskId())
				&& !requestedTask.getTaskId().isEmpty() && Objects.nonNull(requestedTask.getRequiredSkillSet())
				&& !requestedTask.getRequiredSkillSet().isEmpty();

	}

	public void executeTaskAssignment(RoutingContext wdsApiContext) {
		List<Agent> eligibleAgentsList = new ArrayList<>();
		String skillRequired = null;
		Set<Skill> skillSet = TaskAssignmentUtil.getDomainObjectFromRequest(wdsApiContext).getRequiredSkillSet();
		for (Skill skill : skillSet) {
			if (skill != null && skill.getSkillName() != null) {
				skillRequired = skill.getSkillName();
				break;
			}
		}

		agentDao.getAgentWithMatchingSkill(skillRequired, agentSearchHandler -> {
			if (agentSearchHandler.succeeded()) {
				Json.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
					agentSearchHandler.result()
							.forEach(agent -> {
								try {
									eligibleAgentsList.add(Json.mapper.readValue(agent.encode(), Agent.class));
								} catch (IOException ioExcp) {
									// suppress error - e.printStackTrace();
									ioExcp.printStackTrace();
								}
							});
				System.out.println("TaskAssignmentService :: executeTaskAssignment() - Total Available Agents = "+ eligibleAgentsList.size());
			} else {
				System.out.println("TaskAssignmentService :: executeTaskAssignment() - No Agenst found.");
				agentSearchHandler.otherwiseEmpty();
			}
			try {
				assignTaskToAgent(eligibleAgentsList, wdsApiContext);
			} catch (IOException ioExcp) {
				wdsApiContext.fail(500, ioExcp);
				return;
			}
		});
		
	}
	
	private void assignTaskToAgent(List<Agent> eligibleAgentsList, RoutingContext wdsApiContext) throws IOException {
		System.out.println("TaskAssignmentService :: assignTaskToAgent() - Assigning Agent with requested Task.");
		// No Eligible Agent found with the required Skill
		if (Objects.isNull(eligibleAgentsList) || eligibleAgentsList.isEmpty()) {
			System.out.println("TaskAssignmentService :: assignTaskToAgent() - Oops !! Sorry, No Agent with the required skill is found for the Task.");
			wdsApiContext.request().response().setStatusCode(500).setStatusMessage("Oops !! Sorry, No Agent with the required skill found for the Task.");
			wdsApiContext.fail(500);
			return;
		}
		// Task taskRequested =
		// Json.mapper.readValue(wdsApiContext.getBodyAsString("UTF-8"), Task.class);
		Task taskRequested = TaskAssignmentUtil.getDomainObjectFromRequest(wdsApiContext);
		Agent selectedAgent = eligibleAgentsList.stream()
				.filter(availableAgent -> availableAgent.isAvailable()).findFirst()
				.orElse(null);
		// Is Agent with required Skill Available ?
		if ( selectedAgent != null && selectedAgent.isAvailable()) {
			taskRequested.setAssignedAgent(selectedAgent);
			System.out.println("TaskAssignmentService :: assignTaskToAgent() - Task with Assigned Agents is =>" + Json.encodePrettily(taskRequested));
			mapAgentWithTask(taskRequested, wdsApiContext);
			//TODO - Verify if this Response Handling needed here or already taken care in mapAgentWithTask()
			System.out.println("TaskAssignmentService :: taskAssignment() - DONE Assigning Agent to Task");
			wdsApiContext.request().response().setStatusCode(201).end(Json.encode(taskRequested));
			return;
		} else {
			// Get Agent with LOWER Priority Task Compared to Requested Task
			Optional<Agent> agentWithLowPriorityTask = eligibleAgentsList.stream()
					.filter(agent -> taskRequested.getTaskPriority() > agent.getAssignedTaskPriority())
					.findFirst();
			if (agentWithLowPriorityTask.isPresent()) {
				taskRequested.setAssignedAgent(agentWithLowPriorityTask.get());
			} else {
				// With Same Task Priority : Get Agent with Most Recent Task Priority
				Task interruptedTask = taskAssignmentDao.getAgentWithMostRecentTask();
				if (null == interruptedTask) {
					wdsApiContext.request().response().setStatusCode(500).end("Sorry, currently no Agent is available to execute this task. We'll assign this task as soon eligible Agent is available.");
					wdsApiContext.fail(500);
					return;
				}
				taskRequested.setAssignedAgent(interruptedTask.getAssignedAgent());
				// Update Interrupted Task Status
				taskAssignmentDao.updateTask(interruptedTask.getTaskId(), TaskStatus.INPROGRESS.name(), asyncTaskHandler -> {
					if (asyncTaskHandler.succeeded()) {
						System.out.println("TaskAssignmentService :: assignTaskToAgent() - Task Interrupted");
					}
				});
			}
			mapAgentWithTask(taskRequested, wdsApiContext);
		}
	}
	
	private void mapAgentWithTask(Task taskWithAssignedAgent, RoutingContext wdsApiContext) {
		// Save Task with assigned Agent
		taskAssignmentDao.createTask(taskWithAssignedAgent, taskCreator -> {
			if (taskCreator.failed()) {
				System.out.println("TaskAssignmentService :: mapAgentWithTask() - FAILED Assigning Agent to Task");
				wdsApiContext.request().response().setStatusCode(500).end("Error : Failed to Assign Agent to Requed Task.");
				wdsApiContext.fail(500);
				return;
			}
			System.out.println("TaskAssignmentService :: mapAgentWithTask() - DONE Assigning Agent to Task");
			//Task Creation Successful. Update Agent with Current Assigned Task
			Agent assignedAgent = taskWithAssignedAgent.getAssignedAgent();
			assignedAgent.setAvailable(false);
			assignedAgent.setAssignedTaskPriority(taskWithAssignedAgent.getTaskPriority());
			agentDao.updateAgentInfo(assignedAgent, updateHander -> {
				System.out.println("TaskAssignmentService :: mapAgentWithTask() - DONE Updating Agent Assignment");
				if (updateHander.succeeded()) {
					wdsApiContext.request().response().setStatusCode(201).end(Json.encode(taskWithAssignedAgent));
					return;
				} else {
					System.out.println("TaskAssignmentService :: mapAgentWithTask() - FAILED Updating Agent Assignment");
				}
			});
		});
		
	}

	private boolean isAgentEligibleForTask(Agent agent, Task task) {

		Set<Skill> skillSetsRequiredByTask = task.getRequiredSkillSet();
		String agentSkill = agent.getSkill();
		boolean agentEligibility = false;

		// Verify if either Task Skills OR Agent Skills are null
		if (Objects.isNull(agentSkill) || agentSkill.isEmpty() || 
			Objects.isNull(skillSetsRequiredByTask) || skillSetsRequiredByTask.isEmpty()) {
			System.out.println(
					"TaskAssignmentService :: isAgentEligibleForTask() - Either Task Skills OR Agent Skills are null, agentEligibility =>"
							+ agentEligibility);
			return agentEligibility;
		}
		agentEligibility = skillSetsRequiredByTask.stream()
				.anyMatch(requiredSkill -> Objects.nonNull(requiredSkill) && agentSkill.equalsIgnoreCase(requiredSkill.getSkillName()));

		System.out.println("TaskAssignmentService :: isAgentEligibleForTask() - Skill Matching Found =>"+ agentEligibility);
		return agentEligibility;

	}


	public void markTaskCompleted(RoutingContext wdsApiContext) {
		String taskId = wdsApiContext.pathParam(COLUMN_TASK_ID);
		taskAssignmentDao.updateTask(taskId, TaskStatus.COMPLETED.name(), asyncTaskhandler -> {
			if ( null == asyncTaskhandler.result() || asyncTaskhandler.failed()) {
				wdsApiContext.request().response().setStatusCode(500).end("Oops!! Something went wrong. No action needed. We'll process your request in some time.");
				wdsApiContext.fail(asyncTaskhandler.cause());
				return;
			}
			System.out.println("TaskAssignmentService :: markTaskCompleted() - Task Marked as Complete, TaskId =>"
					+ taskId + "\n" + Json.encodePrettily(asyncTaskhandler.result()));
			JsonObject closedTask = asyncTaskhandler.result().put("taskStatus", TaskStatus.COMPLETED.name());
			closedTask.put("available", true);
			closedTask.put("assignedTaskPriority", TaskPriority.NA.getPriority());
			try {
				Agent assignedAgent = Json.decodeValue(closedTask.getString("assignedAgent"), Agent.class);
				
				agentDao.updateAgentInfo(assignedAgent, agentUpdateHandler -> {
					if (agentUpdateHandler.failed()) {
						System.out.println(
								"TaskAssignmentService :: markTaskCompleted() - FAILED to update Agent availability status after task closure.");
						wdsApiContext.request().response().setStatusCode(200).end(Json.encode(closedTask));
						return;
					}
				});
			} catch (Exception excp) {
				wdsApiContext.request().response().setStatusCode(200).end(Json.encode(closedTask));
				return;
			}
			
			wdsApiContext.request().response().setStatusCode(200).end(Json.encode(closedTask));
			return;
		});
	}
	
}
