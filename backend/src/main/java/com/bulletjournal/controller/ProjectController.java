package com.bulletjournal.controller;

import com.bulletjournal.clients.UserClient;
import com.bulletjournal.controller.models.*;
import com.bulletjournal.controller.utils.EtagGenerator;
import com.bulletjournal.repository.ProjectDaoJpa;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;

@RestController
public class ProjectController {

    protected static final String PROJECTS_ROUTE = "/api/projects";
    protected static final String PROJECT_ROUTE = "/api/projects/{projectId}";
    protected static final String UPDATE_SHARED_PROJECTS_ORDER_ROUTE = "/api/updateSharedProjectsOrder";

    @Autowired
    private ProjectDaoJpa projectDaoJpa;

    @GetMapping(PROJECTS_ROUTE)
    public ResponseEntity<Projects> getProjects(HttpEntity<byte[]> requestEntity) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Projects projects = this.projectDaoJpa.getProjects(username);

        int requestHeaderETagSize = requestEntity.getHeaders().getIfNoneMatch().size();
        String requestETag = requestHeaderETagSize > 0 ?
                requestEntity.getHeaders().getIfNoneMatch().get(requestHeaderETagSize - 1) : null;
        String responseETag;

        try {
            responseETag = EtagGenerator.generateEtag(EtagGenerator.HashAlgorithm.MD5,
                    EtagGenerator.HashType.TO_HASHCODE,
                    projects.getOwned(),
                    projects.getShared());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setETag(responseETag);
        if (requestETag != null && responseETag.equals(requestETag)) {
            return new ResponseEntity<>(responseHeaders, HttpStatus.NOT_MODIFIED);
        }

        return ResponseEntity.ok().headers(responseHeaders).body(projects);
    }

    @PostMapping(PROJECTS_ROUTE)
    @ResponseStatus(HttpStatus.CREATED)
    public Project createProject(@Valid @RequestBody CreateProjectParams project) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        return projectDaoJpa.create(project, username).toPresentationModel();
    }

    @PatchMapping(PROJECT_ROUTE)
    public Project updateProject(@NotNull @PathVariable Long projectId,
                                 @Valid @RequestBody UpdateProjectParams updateProjectParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        return this.projectDaoJpa.partialUpdate(username, projectId, updateProjectParams).toPresentationModel();
    }

    /**
     * Delete project deletes its child projects as well
     */
    @DeleteMapping(PROJECT_ROUTE)
    public void deleteProject(@NotNull @PathVariable Long projectId) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.projectDaoJpa.deleteProject(username, projectId);
    }

    @PostMapping(UPDATE_SHARED_PROJECTS_ORDER_ROUTE)
    public void updateSharedProjectsOrder(
            @Valid @RequestBody UpdateSharedProjectsOrderParams updateSharedProjectsOrderParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.projectDaoJpa.updateSharedProjectsOrder(username, updateSharedProjectsOrderParams);
    }

    @PutMapping(PROJECTS_ROUTE)
    public void updateProjectRelations(@Valid @RequestBody List<Project> projects) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.projectDaoJpa.updateUserOwnedProjects(username, projects);
    }
}