package com.bulletjournal.repository.models;

import com.bulletjournal.contents.ContentType;
import com.bulletjournal.controller.models.Label;
import com.bulletjournal.controller.models.TaskStatus;

import javax.persistence.*;
import java.util.List;

/**
 * This class is for ProjectType.TODO
 */
@Entity
@Table(name = "tasks", uniqueConstraints = { @UniqueConstraint(columnNames = { "google_calendar_event_id" }) })
public class Task extends TaskModel {
    @Id
    @GeneratedValue(generator = "task_generator")
    @SequenceGenerator(name = "task_generator", sequenceName = "task_sequence", initialValue = 100)
    private Long id;

    @Column
    private Integer status;

    @Column(name = "completed_slots", columnDefinition = "TEXT")
    private String completedSlots;

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    @Override
    public ContentType getContentType() {
        return ContentType.TASK;
    }

    public String getCompletedSlots() {
        return completedSlots;
    }

    public void setCompletedSlots(String completedSlots) {
        this.completedSlots = completedSlots;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    @Override
    public com.bulletjournal.controller.models.Task toPresentationModel() {
        com.bulletjournal.controller.models.Task task = super.toPresentationModel();
        if (this.status != null) {
            task.setStatus(TaskStatus.getType(this.status));
        }
        return task;
    }

    @Override
    public com.bulletjournal.controller.models.Task toPresentationModel(List<Label> labels) {

        com.bulletjournal.controller.models.Task task = super.toPresentationModel(labels);
        if (this.status != null) {
            task.setStatus(TaskStatus.getType(this.status));
        }
        return task;
    }

}
