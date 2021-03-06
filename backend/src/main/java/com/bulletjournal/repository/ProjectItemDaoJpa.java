package com.bulletjournal.repository;

import com.bulletjournal.authz.AuthorizationService;
import com.bulletjournal.authz.Operation;
import com.bulletjournal.config.ContentRevisionConfig;
import com.bulletjournal.contents.ContentAction;
import com.bulletjournal.contents.ContentType;
import com.bulletjournal.controller.models.*;
import com.bulletjournal.exceptions.BadRequestException;
import com.bulletjournal.exceptions.ResourceNotFoundException;
import com.bulletjournal.notifications.*;
import com.bulletjournal.repository.models.ContentModel;
import com.bulletjournal.repository.models.Group;
import com.bulletjournal.repository.models.ProjectItemModel;
import com.bulletjournal.repository.models.UserGroup;
import com.bulletjournal.util.ContentDiffTool;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public abstract class ProjectItemDaoJpa<K extends ContentModel> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectItemDaoJpa.class);
    private static final Gson GSON = new Gson();

    @Autowired
    protected LabelDaoJpa labelDaoJpa;
    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private GroupDaoJpa groupDaoJpa;
    @Autowired
    private SharedProjectItemDaoJpa sharedProjectItemDaoJpa;
    @Autowired
    private PublicProjectItemDaoJpa publicProjectItemDaoJpa;
    @Autowired
    private ContentRevisionConfig revisionConfig;
    @Autowired
    private ContentDiffTool contentDiffTool;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private NotificationService notificationService;

    abstract <T extends ProjectItemModel> JpaRepository<T, Long> getJpaRepository();

    abstract JpaRepository<K, Long> getContentJpaRepository();

    abstract <T extends ProjectItemModel> List<K> findContents(T projectItem);

    abstract List<Long> findItemLabelsByProject(com.bulletjournal.repository.models.Project project);

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> SharableLink generatePublicItemLink(Long projectItemId, String requester,
                                                                            Long ttl) {
        T projectItem = getProjectItem(projectItemId, requester);
        SharableLink sharableLink = this.publicProjectItemDaoJpa.generatePublicItemLink(projectItem, requester, ttl);
        this.notificationService.trackActivity(
                new Auditable(projectItem.getProject().getId(),
                        "shared " + projectItem.getContentType() +
                                " ##" + projectItem.getName() + "## in BuJo ##" + projectItem.getProject().getName() +
                                "## with link ##" + sharableLink.getLink() + "##",
                        requester, projectItemId, Timestamp.from(Instant.now()), ContentAction.SHARE));
        return sharableLink;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> ShareProjectItemEvent shareProjectItem(Long projectItemId,
                                                                               ShareProjectItemParams shareProjectItemParams, String requester) {
        T projectItem = getProjectItem(projectItemId, requester);
        List<String> users = new ArrayList<>();
        if (StringUtils.isNotBlank(shareProjectItemParams.getTargetUser())) {
            users.add(shareProjectItemParams.getTargetUser());
        }

        if (shareProjectItemParams.getTargetGroup() != null) {
            Group group = this.groupDaoJpa.getGroup(shareProjectItemParams.getTargetGroup());
            for (UserGroup userGroup : group.getAcceptedUsers()) {
                users.add(userGroup.getUser().getName());
            }
        }

        ProjectType projectType = ProjectType.getType(projectItem.getProject().getType());
        ShareProjectItemEvent event = this.sharedProjectItemDaoJpa.save(projectType, projectItem, users, requester);
        this.notificationService.trackActivity(new Auditable(
                projectItem.getProject().getId(),
                "shared " + projectItem.getContentType() +
                        " ##" + projectItem.getName() + "## in BuJo ##" + projectItem.getProject().getName() +
                        "## with " +
                        (users.size() == 1 ? "user ##" + users.get(0) :
                                "users ##" + users.toString().substring(1, users.toString().length() - 1))
                        + "##",
                requester, projectItemId, Timestamp.from(Instant.now()), ContentAction.SHARE));
        return event;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> ProjectItemSharables getSharables(Long projectItemId, String requester) {
        T projectItem = getProjectItem(projectItemId, requester);

        List<SharableLink> links = this.publicProjectItemDaoJpa.getPublicItemLinks(projectItem).stream()
                .map(item -> item.toSharableLink()).collect(Collectors.toList());
        Set<String> users = this.sharedProjectItemDaoJpa.getProjectItemSharedUsers(projectItem).stream()
                .map(item -> item.getUsername()).collect(Collectors.toSet());
        ProjectItemSharables result = new ProjectItemSharables();
        result.setLinks(links);
        result.setUsers(users.stream().map(u -> new User(u)).collect(Collectors.toList()));
        return result;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> RevokeSharableEvent revokeSharable(Long projectItemId, String requester,
                                                                           RevokeProjectItemSharableParams revokeProjectItemSharableParams) {
        T projectItem = getProjectItem(projectItemId, requester);

        String link = revokeProjectItemSharableParams.getLink();
        if (link != null) {
            this.publicProjectItemDaoJpa.revokeSharableLink(projectItem, link);
        }

        String user = revokeProjectItemSharableParams.getUser();
        if (user != null) {
            this.sharedProjectItemDaoJpa.deleteSharedProjectItemWithUser(projectItem, user);
            return new RevokeSharableEvent(new Event(user, projectItemId, projectItem.getName()), requester,
                    projectItem.getContentType());
        }

        return null;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> void removeShared(Long projectItemId, String requester) {
        T projectItem = getProjectItem(projectItemId, requester);
        this.sharedProjectItemDaoJpa.deleteSharedProjectItemWithUser(projectItem, requester);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> Pair<ContentModel, T> addContent(Long projectItemId, String owner, K content) {
        T projectItem = getProjectItem(projectItemId, owner);
        content.setProjectItem(projectItem);
        content.setOwner(owner);
        updateRevision(content, content.getText(), owner);
        this.getContentJpaRepository().save(content);
        return Pair.of(content, projectItem);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public K getContent(Long contentId, String requester) {
        K content = this.getContentJpaRepository().findById(contentId)
                .orElseThrow(() -> new ResourceNotFoundException("Content " + contentId + " not found"));
        this.authorizationService.validateRequesterInProjectGroup(requester, content.getProjectItem());
        return content;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> Pair<K, T> updateContent(Long contentId, Long projectItemId, String requester,
                                                                 UpdateContentParams updateContentParams) {
        T projectItem = getProjectItem(projectItemId, requester);
        K content = getContent(contentId, requester);
        Preconditions.checkState(Objects.equals(projectItem.getId(), content.getProjectItem().getId()),
                "ProjectItem ID mismatch");
        this.authorizationService.checkAuthorizedToOperateOnContent(content.getOwner(), requester, ContentType.CONTENT,
                Operation.UPDATE, content.getId(), projectItem.getOwner(), projectItem.getProject().getOwner(),
                projectItem);
        updateRevision(content, updateContentParams.getText(), requester);
        content.setText(updateContentParams.getText());
        this.getContentJpaRepository().save(content);
        return Pair.of(content, projectItem);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> T deleteContent(Long contentId, Long projectItemId, String requester) {
        T projectItem = getProjectItem(projectItemId, requester);
        K content = getContent(contentId, requester);
        Preconditions.checkState(Objects.equals(projectItem.getId(), content.getProjectItem().getId()),
                "ProjectItem ID mismatch");
        this.authorizationService.checkAuthorizedToOperateOnContent(content.getOwner(), requester, ContentType.CONTENT,
                Operation.DELETE, content.getId(), projectItem.getOwner(), projectItem.getProject().getOwner(),
                projectItem);
        this.getContentJpaRepository().delete(content);
        return projectItem;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> T getProjectItem(Long projectItemId, String requester) {
        ProjectItemModel projectItem = this.getJpaRepository().findById(projectItemId)
                .orElseThrow(() -> new ResourceNotFoundException("projectItem " + projectItemId + " not found"));
        this.authorizationService.validateRequesterInProjectGroup(requester, projectItem);
        return (T) projectItem;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    protected <T extends ProjectItemModel> List<com.bulletjournal.controller.models.Label> getLabelsToProjectItem(
            T projectItem) {
        return this.labelDaoJpa.getLabels(projectItem.getLabels());
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public SetLabelEvent setLabels(String requester, Long projectItemId, List<Long> labels) {
        ProjectItemModel projectItem = getProjectItem(projectItemId, requester);
        projectItem.setLabels(labels);
        Set<UserGroup> targetUsers = projectItem.getProject().getGroup().getAcceptedUsers();
        List<Event> events = new ArrayList<>();
        for (UserGroup user : targetUsers) {
            if (!Objects.equals(user.getUser().getName(), requester)) {
                events.add(new Event(user.getUser().getName(), projectItemId, projectItem.getName()));
            }
        }

        this.getJpaRepository().save(projectItem);
        return new SetLabelEvent(events, requester, projectItem.getContentType());
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> Revision getContentRevision(String requester, Long projectItemId,
                                                                    Long contentId, Long revisionId) {
        T projectItem = getProjectItem(projectItemId, requester);
        K content = getContent(contentId, requester);
        Preconditions.checkState(Objects.equals(projectItem.getId(), content.getProjectItem().getId()),
                "ProjectItem ID mismatch");
        Revision[] revisions = GSON.fromJson(content.getRevisions(), Revision[].class);
        Preconditions.checkNotNull(revisions, "Revisions for Content: {} is null", contentId);
        if (!Arrays.stream(revisions).anyMatch(revision -> Objects.equals(revision.getId(), revisionId))) {
            throw new BadRequestException("Invalid revisionId: " + revisionId + " for content: " + contentId);
        }

        if (revisionId.equals(revisions[revisions.length - 1].getId())) {
            revisions[revisions.length - 1].setContent(content.getText());
            return revisions[revisions.length - 1];
        }

        String ret = content.getBaseText();
        for (Revision revision : revisions) {
            ret = contentDiffTool.applyDiff(ret, revision.getDiff());
            if (revision.getId().equals(revisionId)) {
                revision.setContent(ret);
                return revision;
            }
        }
        throw new IllegalStateException("Cannot reach here");
    }

    private void updateRevision(K content, String newText, String requester) {
        String revisionsJson = content.getRevisions();
        if (revisionsJson == null) {
            revisionsJson = "[]";
        }
        LinkedList<Revision> revisionList = new LinkedList<>(
                Arrays.asList(GSON.fromJson(revisionsJson, Revision[].class)));
        int maxRevisionNumber = revisionConfig.getMaxRevisionNumber();
        long nextRevisionId;
        if (revisionList.isEmpty()) {
            content.setBaseText(content.getText());
            nextRevisionId = 1;
        } else {
            nextRevisionId = revisionList.getLast().getId() + 1;
            if (revisionList.size() == maxRevisionNumber) {
                String oldBaseText = content.getBaseText();
                String diffToMerge = revisionList.pollFirst().getDiff();
                content.setBaseText(contentDiffTool.applyDiff(oldBaseText, diffToMerge));
            }
        }
        String diff = contentDiffTool.computeDiff(content.getText(), newText);
        Revision newRevision = new Revision(nextRevisionId, diff, Instant.now().toEpochMilli(), new User(requester));
        revisionList.offerLast(newRevision);
        content.setRevisions(GSON.toJson(revisionList));
    }

    /**
     * Get Contents for project
     *
     * @param projectItemId the project item id
     * @param requester     the username of action requester
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> List<K> getContents(Long projectItemId, String requester) {
        T projectItem = getProjectItem(projectItemId, requester);
        return this.findContents(projectItem).stream().sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    abstract <T extends ProjectItemModel> List<T> findRecentProjectItemsBetween(Timestamp startTime, Timestamp endTime, List projects);

    abstract List<Object[]> findRecentProjectItemContentsBetween(Timestamp startTime, Timestamp endTime, List projectIds);

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public <T extends ProjectItemModel> List<T> getRecentProjectItemsBetween(Timestamp startTime, Timestamp endTime, List<Long> projectIds) {
        Map<Long, T> projectItemIdMap = new HashMap<>();

        List<T> projectItemModels = this.findRecentProjectItemsBetween(startTime, endTime,
                projectRepository.findAllById(projectIds));
        projectItemModels.forEach(item -> projectItemIdMap.put(item.getId(), item));

        List<Object[]> projectItemJoinContentModels = this.findRecentProjectItemContentsBetween(startTime, endTime, projectIds);
        projectItemJoinContentModels.forEach(item -> {
            Long projectItemId = null;
            Timestamp mostRecentTime = null;
            for (Object o : item) {
                if (projectItemId == null) {
                    projectItemId = ((BigInteger) o).longValue();
                } else {
                    mostRecentTime = (Timestamp) o;
                }
            }
            if (!projectItemIdMap.containsKey(projectItemId)) {
                T projectItem = (T) this.getJpaRepository().findById(projectItemId).get();
                projectItem.setUpdatedAt(mostRecentTime);
                projectItemIdMap.put(projectItemId, projectItem);
            } else if (projectItemIdMap.get(projectItemId).getUpdatedAt().compareTo(mostRecentTime) == -1)
                projectItemIdMap.get(projectItemId).setUpdatedAt(mostRecentTime);
        });


        return new ArrayList<>(projectItemIdMap.values());
    }
}

