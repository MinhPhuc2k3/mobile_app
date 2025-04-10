package org.example.management.management.domain.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.example.management.management.application.converter.IntListConverter;
import org.example.management.management.application.converter.StringListConverter;
import org.example.management.management.application.model.task.TaskImageRequest;
import org.example.management.management.domain.leaves.Leave;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.*;

@Getter
@Entity
@Table(name = "tasks")
public class Task {

    @Setter
    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "product_management_id", referencedColumnName = "id")
    private ProjectManagement aggRoot;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String description;

    @Positive
    private int projectId;

    //NOTE: Hiện tại mới support 1 task (1 assignee, 1 user)
    @Positive
    private Integer assignId; // userId

    @Positive
    private Integer processId;

    @Enumerated(value = EnumType.STRING)
    private Priority priority;

    @Enumerated(value = EnumType.STRING)
    private Difficulty difficulty;

    @Enumerated(value = EnumType.STRING)
    private Status status;

    @CreationTimestamp
    private Instant createdOn;

    @UpdateTimestamp
    private Instant modifiedOn;

    private Instant finishedOn;

    @Convert(converter = IntListConverter.class)
    private List<Integer> imageIds = new ArrayList<>();

    @OneToOne(mappedBy = "task", orphanRemoval = true)
    @PrimaryKeyJoinColumn
    private Leave leave;

    protected Task() {
    }

    public Task(
            String title,
            String description,
            int projectId,
            Integer assignId,
            Integer processId,
            Priority priority,
            Difficulty difficulty,
            Status status
    ) {
        this.title = title;
        this.description = description;

        this.projectId = projectId;

        this.assignId = assignId;
        this.processId = processId;

        this.priority = priority;
        this.difficulty = difficulty;
        this.status = status;
    }

    public void updateUser(Integer assignId, Integer processId) {
        if (!Objects.equals(assignId, this.assignId)) {
            this.internalSetAssignedUser(assignId);
        }
        if (!Objects.equals(processId, this.processId)) {
            this.internalSetProcessId(processId);
        }
    }

    private void internalSetProcessId(Integer processId) {
        this.processId = processId;
        //TODO: Bổ sung thêm event để làm thông báo
    }

    private void internalSetAssignedUser(Integer assignId) {
        this.assignId = assignId;
        //TODO: Bổ sung thêm event để làm thông báo
    }

    public void update(Priority priority, Difficulty difficulty, Status status) {
        this.internalSetPriority(priority);
        this.internalSetDifficulty(difficulty);
        this.internalSetStatus(status);
        //TODO: Bổ sung thêm event để làm thông báo
    }

    private void internalSetStatus(Status status) {
        if (Objects.equals(this.status, status)) {
            return;
        }
        this.status = status;
    }

    private void internalSetDifficulty(Difficulty difficulty) {
        if (Objects.equals(this.difficulty, difficulty)) {
            return;
        }

        this.difficulty = difficulty;
    }

    private void internalSetPriority(Priority priority) {
        if (Objects.equals(priority, this.priority)) {
            return;
        }

        this.priority = priority;
    }

    public void markupFinished(Instant finishedOn) {
        this.status = Status.finish;
        this.finishedOn = finishedOn;

        //TODO: event
    }

    public void setImages(List<Image> images) {
        if (CollectionUtils.isEmpty(images)) {
            return;
        }

        this.imageIds = images.stream()
                .map(Image::getId)
                .toList();
    }

    public List<Integer> updateImagesAndGetImageDeleted(Map<Integer, TaskImageRequest> updateImages, List<Image> newImages) {
        if (updateImages == null) updateImages = new LinkedHashMap<>();
        if (newImages == null) newImages = new ArrayList<>();

        List<Integer> imagesDeleted = new ArrayList<>();

        Map<Integer, TaskImageRequest> finalUpdateImages = updateImages;
        this.imageIds.removeIf(id -> {
            if (!finalUpdateImages.containsKey(id)) {
                imagesDeleted.add(id);
                return true;
            }
            return false;
        });

        newImages.forEach(image -> this.imageIds.add(image.getId()));

        return imagesDeleted;
    }

    public void addImage(int id) {
        if (this.imageIds == null) this.imageIds = new ArrayList<>();
        this.imageIds.add(id);
    }

    public enum Status {
        to_do,
        in_process,
        finish
    }

    public enum Difficulty {
        very_easy,
        easy // TODO: Thêm
    }

    public enum Priority {
        low,
        medium,
        high
    }
}
