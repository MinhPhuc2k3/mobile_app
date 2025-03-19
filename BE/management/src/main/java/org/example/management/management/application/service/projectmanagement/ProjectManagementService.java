package org.example.management.management.application.service.projectmanagement;

import com.google.common.collect.Streams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.management.management.application.model.task.TaskResponse;
import org.example.management.management.application.model.user.response.UserResponse;
import org.example.management.management.application.service.projects.ProjectCreatedEvent;
import org.example.management.management.application.service.task.TaskService;
import org.example.management.management.application.service.user.UserService;
import org.example.management.management.application.utils.ArrayUtils;
import org.example.management.management.application.utils.NumberUtils;
import org.example.management.management.domain.profile.User;
import org.example.management.management.domain.project.Project;
import org.example.management.management.domain.task.ProjectManagement;
import org.example.management.management.domain.task.ProjectTasKManagement;
import org.example.management.management.domain.task.Task;
import org.example.management.management.infastructure.exception.ConstrainViolationException;
import org.example.management.management.infastructure.persistance.JpaUserRepositoryInterface;
import org.example.management.management.infastructure.persistance.ProjectManagementRepository;
import org.example.management.management.infastructure.persistance.ProjectRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectManagementService {

    private final ProjectRepository projectRepository;
    private final JpaUserRepositoryInterface userRepository;

    private final ProjectManagementRepository projectManagementRepository;

    private final UserService userService;
    private final TaskService taskService;

    public List<ProjectManagement> handleProjectCreated(ProjectCreatedEvent event) {
        int projectId = event.projectId();

        var userInProjectInfo = event.userInProjectInfo();
        List<Integer> userRemovedIds = userInProjectInfo.removeIds();
        List<Integer> userAddedIds = userInProjectInfo.addIds();

        if (CollectionUtils.isEmpty(userRemovedIds)
                && CollectionUtils.isEmpty(userAddedIds)) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping handle project management because all user input are empty in project {}", projectId);
            }
            return null;
        }

        var project = projectRepository.findById(projectId)
                .orElseThrow(IllegalArgumentException::new);

        var users = this.validateUserIds(userRemovedIds, userAddedIds);

        var projectManagements = projectManagementRepository.findByProjectIdAndUserIdIn(project.getId(), users.keySet());

        //NOTE: Nếu như chưa có thì sẽ tạo
        if (CollectionUtils.isEmpty(projectManagements)) {
            return createProjectManagement(project, userAddedIds, users);
        }

        return updateManagement(project, userRemovedIds, userAddedIds, users);
    }

    private List<ProjectManagement> updateManagement(
            Project project,
            List<Integer> userRemovedIds,
            List<Integer> userAddedIds,
            Map<Integer, User> userFetched
    ) {

        int projectId = project.getId();

        List<ProjectManagement> projectManagementRemoved = new ArrayList<>();
        List<ProjectManagement> projectManagementAdded = new ArrayList<>();

        if (CollectionUtils.isNotEmpty(userRemovedIds)) {
            projectManagementRemoved = this.projectManagementRepository.findByProjectIdAndUserIdIn(projectId, new HashSet<>(userRemovedIds));
        }

        if (CollectionUtils.isNotEmpty(userAddedIds)) {
            projectManagementAdded = this.createProjectManagement(project, userAddedIds, userFetched);
        }

        if (CollectionUtils.isNotEmpty(projectManagementRemoved)) {
            log.info("Removing project managements with user_ids = {}", userRemovedIds);
            this.projectManagementRepository.deleteAll(projectManagementRemoved);
        }
        return projectManagementAdded;
    }

    private List<ProjectManagement> createProjectManagement(Project project, List<Integer> userAddedIds, Map<Integer, User> users) {
        List<ProjectManagement> projectManagements = userAddedIds.stream()
                .map(userId ->
                        new ProjectManagement(
                                project.getId(),
                                userId
                        ))
                .toList();
        return projectManagementRepository.saveAll(projectManagements);
    }

    private Map<Integer, User> validateUserIds(List<Integer> userRemovedIds, List<Integer> userAddedIds) {
        var allUserIds = Streams.concat(userRemovedIds.stream(), userAddedIds.stream()).toList();
        var userMap = userRepository.findByIdIn(allUserIds)
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        String userNotFound = allUserIds.stream()
                .filter(id -> !userMap.containsKey(id))
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        if (StringUtils.isNotEmpty(userNotFound)) {
            throw new ConstrainViolationException("user", "user not found " + userNotFound);
        }

        return userMap;
    }

    public List<TaskManagementInfo> getTaskInfo(List<Integer> projectIds) {
        if (CollectionUtils.isEmpty(projectIds)) {
            return List.of();
        }

        List<ProjectManagement> projectManagements = this.projectManagementRepository.findByProjectIdIn(projectIds);
        if (CollectionUtils.isEmpty(projectManagements)) {
            return List.of();
        }

        Map<Integer, Map<Integer, List<ProjectTasKManagement>>> managementMap = projectManagements.stream()
                .collect(Collectors.groupingBy(
                        ProjectManagement::getProjectId,
                        Collectors.groupingBy(
                                ProjectManagement::getUserId,
                                Collectors.reducing(
                                        new ArrayList<>(),
                                        ProjectManagement::getManagements,
                                        (list1, list2) -> Streams.concat(ArrayUtils.convert(list1), ArrayUtils.convert(list2)).toList()
                                )
                        )
                ));

        List<Integer> userIds = managementMap.values().stream()
                .flatMap(map -> map.keySet().stream())
                .toList();
        List<UserResponse> users = this.userService.getByIds(userIds);

        List<Integer> allTaskIds = managementMap.values().stream()
                .flatMap(map -> map.values().stream().flatMap(Collection::stream))
                .map(ProjectTasKManagement::getTaskId)
                .toList();

        List<TaskResponse> tasks = this.taskService.getByIds(allTaskIds);

        List<TaskManagementInfo> results = new ArrayList<>();
        for (var projectId : projectIds) {
            var userMap = managementMap.getOrDefault(projectId, Map.of());
            if (userMap.isEmpty()) {
                results.add(new TaskManagementInfo(projectId, List.of(), List.of()));
                continue;
            }

            var userOfProjectIds = userMap.keySet();
            var usersOfProject = users.stream()
                    .filter(u -> userOfProjectIds.contains(u.getId()))
                    .toList();

            var taskIds = userMap.values().stream()
                    .flatMap(Collection::stream)
                    .map(ProjectTasKManagement::getTaskId)
                    .collect(Collectors.toSet());
            var tasksOfProject = tasks.stream()
                    .filter(t -> taskIds.contains(t.getId()))
                    .toList();

            results.add(new TaskManagementInfo(projectId, usersOfProject, tasksOfProject));
        }

        return results;
    }

    @EventListener(classes = TaskService.CreateTaskManagement.class)
    public void createTaskManagement(TaskService.CreateTaskManagement event) {
        if (!NumberUtils.isPositive(event.userId())) {
            return;
        }

        int projectId = event.projectId();
        int userId = event.userId();
        var task = event.task();

        var projectManagements = this.projectManagementRepository.findByProjectIdAndUserIdIn(projectId, Set.of(userId));

        //NOTE: Nếu chưa thêm user vào project mà thêm task => thêm projectManagement, projectTaskManagement luôn
        ProjectManagement projectManagement;
        if (CollectionUtils.isEmpty(projectManagements)) {
            projectManagement = createNewProjectManagementIfNotExists(projectId, userId);
        } else {
            projectManagement = projectManagements.get(0);
        }

        var taskManagements = buildTaskManagements(List.of(task));
        projectManagement.addManagements(taskManagements);

        this.projectManagementRepository.save(projectManagement);
    }

    private List<ProjectTasKManagement> buildTaskManagements(List<Task> tasks) {
        return tasks.stream()
                .map(task -> new ProjectTasKManagement(task.getId()))
                .toList();
    }

    private ProjectManagement createNewProjectManagementIfNotExists(int projectId, Integer userId) {
        return new ProjectManagement(
                projectId,
                userId
        );
    }

    @EventListener(classes = TaskService.UpdateTasKManagement.class)
    public void updateTaskManagement(TaskService.UpdateTasKManagement event) {
        int projectId = event.projectId();
        Integer oldUserId = event.oldUserId();
        Integer newUserId = event.newUserId();
        var task = event.task();

        if (Objects.equals(oldUserId, newUserId)) {
            return;
        }

        List<ProjectManagement> projectManagements = oldUserId != null
                ? this.projectManagementRepository.findByProjectIdAndUserIdIn(projectId, Set.of(oldUserId))
                : new ArrayList<>();

        if (CollectionUtils.isEmpty(projectManagements)) {
            this.projectManagementRepository.deleteAll(projectManagements);
        }

        if (NumberUtils.isPositive(newUserId)) {
            var projectManagement = createNewProjectManagementIfNotExists(projectId, newUserId);

            var taskManagements = buildTaskManagements(List.of(task));
            projectManagement.addManagements(taskManagements);

            this.projectManagementRepository.save(projectManagement);
        }
    }

    public record TaskManagementInfo(int projectId, List<UserResponse> users, List<TaskResponse> tasks) {
    }
}
