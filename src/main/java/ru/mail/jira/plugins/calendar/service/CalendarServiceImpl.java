package ru.mail.jira.plugins.calendar.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.filter.SearchRequestService;
import com.atlassian.jira.exception.GetException;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.search.SearchRequest;
import com.atlassian.jira.issue.search.SearchRequestManager;
import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.I18nHelper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.jira.plugins.calendar.model.Calendar;
import ru.mail.jira.plugins.calendar.model.Permission;
import ru.mail.jira.plugins.calendar.model.SubjectType;
import ru.mail.jira.plugins.calendar.model.UserCalendar;
import ru.mail.jira.plugins.calendar.rest.dto.CalendarDto;
import ru.mail.jira.plugins.calendar.rest.dto.CalendarSettingDto;
import ru.mail.jira.plugins.calendar.rest.dto.PermissionItemDto;
import ru.mail.jira.plugins.commons.CommonUtils;
import ru.mail.jira.plugins.commons.RestFieldException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class CalendarServiceImpl implements CalendarService {
    private final static Logger log = LoggerFactory.getLogger(CalendarServiceImpl.class);

    private final static Pattern COLOR_PATTERN = Pattern.compile("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$");

    public static final String DESCRIPTION = "common.words.description";
    public static final String STATUS = "common.words.status";
    public static final String LABELS = "common.concepts.labels";
    public static final String COMPONENTS = "common.concepts.components";
    public static final String DUEDATE = "issue.field.duedate";
    public static final String ENVIRONMENT = "common.words.env";
    public static final String PRIORITY = "issue.field.priority";
    public static final String RESOLUTION = "issue.field.resolution";
    public static final String AFFECT = "issue.field.version";
    public static final String CREATED = "issue.field.created";
    public static final String UPDATED = "issue.field.updated";
    public static final String REPORTER = "issue.field.reporter";
    public static final String ASSIGNEE = "issue.field.assignee";
    public static final List<String> DISPLAYED_FIELDS = new ArrayList<String>() {{
        add(DESCRIPTION);
        add(STATUS);
        add(ASSIGNEE);
        add(REPORTER);
        add(PRIORITY);
        add(CREATED);
        add(UPDATED);
        add(DUEDATE);
        add(COMPONENTS);
        add(ENVIRONMENT);
        add(LABELS);
        add(RESOLUTION);
        add(AFFECT);
    }};

    private ActiveObjects ao;
    private CustomFieldManager customFieldManager;
    private I18nHelper i18nHelper;
    private GroupManager groupManager;
    private PermissionManager permissionManager;
    private PermissionService permissionService;
    private ProjectManager projectManager;
    private ProjectRoleManager projectRoleManager;
    private SearchRequestService searchRequestService;
    private SearchRequestManager searchRequestManager;
    private UserCalendarService userCalendarService;
    private UserManager userManager;

    public void setAo(ActiveObjects ao) {
        this.ao = ao;
    }

    public void setCustomFieldManager(CustomFieldManager customFieldManager) {
        this.customFieldManager = customFieldManager;
    }

    public void setI18nHelper(I18nHelper i18nHelper) {
        this.i18nHelper = i18nHelper;
    }

    public void setPermissionManager(PermissionManager permissionManager) {
        this.permissionManager = permissionManager;
    }

    public void setPermissionService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public void setProjectManager(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    public void setSearchRequestService(SearchRequestService searchRequestService) {
        this.searchRequestService = searchRequestService;
    }

    public void setSearchRequestManager(SearchRequestManager searchRequestManager) {
        this.searchRequestManager = searchRequestManager;
    }

    public void setUserCalendarService(UserCalendarService userCalendarService) {
        this.userCalendarService = userCalendarService;
    }

    public void setGroupManager(GroupManager groupManager) {
        this.groupManager = groupManager;
    }

    public void setProjectRoleManager(ProjectRoleManager projectRoleManager) {
        this.projectRoleManager = projectRoleManager;
    }

    public void setUserManager(UserManager userManager) {
        this.userManager = userManager;
    }

    public Calendar getCalendar(final int id) throws GetException {
        Calendar calendar = ao.get(Calendar.class, id);
        if (calendar == null)
            throw new GetException("No Calendar with id=" + id);
        return calendar;
    }

    public CalendarSettingDto getCalendarSettingDto(ApplicationUser user, int id) throws GetException {
        CalendarSettingDto result = new CalendarSettingDto();
        Calendar calendar = getCalendar(id);
        result.setSelectedName(calendar.getName());
        result.setSelectedColor(calendar.getColor());
        result.setSelectedEventStartId(calendar.getEventStart());
        result.setSelectedEventEndId(calendar.getEventEnd());

        if (StringUtils.isNotEmpty(calendar.getDisplayedFields()))
            result.setSelectedDisplayedFields(Arrays.asList(calendar.getDisplayedFields().split(",")));

        fillSelectedSourceFields(user, result, calendar);

        result.setCanAdmin(permissionService.hasAdminPermission(user, calendar));

        List<PermissionItemDto> permissions = new ArrayList<PermissionItemDto>();
        for (Permission permission : calendar.getPermissions()) {
            SubjectType subjectType = permission.getSubjectType();
            PermissionItemDto itemDto = null;
            switch (subjectType) {
                case USER:
                    ApplicationUser subjectUser = userManager.getUserByKey(permission.getSubject());
                    if (subjectUser != null)
                        itemDto = PermissionItemDto.buildUserDto(subjectUser.getKey(), subjectUser.getDisplayName(), subjectUser.getEmailAddress(), subjectUser.getName(),
                                                                 PermissionUtils.getAccessType(permission.isAdmin(), permission.isUse()),
                                                                 permissionService.getPermissionAvatar(permission, subjectType));
                    break;
                case GROUP:
                    Group group = groupManager.getGroup(permission.getSubject());
                    if (group != null)
                        itemDto = PermissionItemDto.buildGroupDto(permission.getSubject(), group.getName(),
                                                                  PermissionUtils.getAccessType(permission.isAdmin(), permission.isUse()));
                    break;
                case PROJECT_ROLE:
                    Long projectId = PermissionUtils.getProject(permission.getSubject());
                    Long projectRoleId = PermissionUtils.getProjectRole(permission.getSubject());
                    if (projectId == null || projectRoleId == null)
                        break;
                    Project project = projectManager.getProjectObj(projectId);
                    ProjectRole projectRole = projectRoleManager.getProjectRole(projectRoleId);
                    String projectName = null;
                    String projectRoleName = projectRole != null ? projectRole.getName() : null;
                    if (project != null && permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, project, user, false))
                        projectName = project.getName();
                    itemDto = PermissionItemDto.buildProjectRoleDto(permission.getSubject(), projectName, projectRoleName,
                                                                    PermissionUtils.getAccessType(permission.isAdmin(), permission.isUse()),
                                                                    permissionService.getPermissionAvatar(permission, SubjectType.PROJECT_ROLE));
                    break;
            }
            if (itemDto != null)
                permissions.add(itemDto);
        }
        if (permissions.size() > 0)
            result.setPermissions(permissions);
        return result;
    }

    public CalendarDto[] getAllCalendars(final ApplicationUser user) {
        return fillUserCalendarDtos(user, ao.find(Calendar.class));
    }

    public CalendarDto createCalendar(final ApplicationUser user, final CalendarSettingDto calendarSettingDto) throws GetException {
        validateCalendar(user, calendarSettingDto, true);
        Calendar calendar = ao.create(Calendar.class);
        calendar.setAuthorKey(user.getKey());
        setCalendarFields(calendar, calendarSettingDto);

        permissionService.updatePermissions(calendar, calendarSettingDto.getPermissions());
        userCalendarService.addCalendarToUser(user.getKey(), calendar, true);
        CalendarDto result = new CalendarDto(null, calendar);
        result.setFavorite(true);
        result.setVisible(true);

        //update OneToMany entities after saving
        calendar = getCalendar(calendar.getID());
        boolean canAdmin = permissionService.hasAdminPermission(user, calendar);
        boolean canUse = canAdmin || permissionService.hasUsePermission(user, calendar);
        result.setChangable(canAdmin);
        result.setViewable(canUse);
        return result;
    }

    public CalendarDto updateCalendar(final ApplicationUser user, final CalendarSettingDto calendarSettingDto) throws GetException {
        Calendar calendar = getCalendar(calendarSettingDto.getId());
        if (!permissionService.hasAdminPermission(user, calendar))
            throw new SecurityException("No permission to edit calendar");

        validateCalendar(user, calendarSettingDto, false);
        setCalendarFields(calendar, calendarSettingDto);
        permissionService.updatePermissions(calendar, calendarSettingDto.getPermissions());

        //update OneToMany entities after saving
        calendar = getCalendar(calendarSettingDto.getId());
        UserCalendar userCalendar = userCalendarService.find(calendar.getID(), user.getKey());
        boolean canAdmin = permissionService.hasAdminPermission(user, calendar);
        boolean canUse = canAdmin || permissionService.hasUsePermission(user, calendar);
        return buildCalendarOutput(user, userCalendar, calendar, canUse, canAdmin, userCalendar != null && userCalendar.isEnabled(), userCalendar != null, userCalendarService.getUsersCount(calendar.getID()));
    }

    public void deleteCalendar(final ApplicationUser user, final int calendarId) throws GetException {
        Calendar calendar = getCalendar(calendarId);
        if (!permissionService.hasAdminPermission(user, calendar))
            throw new SecurityException("No permission to edit calendar");
        permissionService.removeCalendarPermissions(calendar);
        userCalendarService.removeCalendar(user.getKey(), calendarId);
        ao.delete(calendar);
    }

    public void updateCalendarVisibility(final int calendarId, final ApplicationUser user, final boolean visible) {
        try {
            userCalendarService.updateCalendarVisibility(calendarId, user.getKey(), visible);
        } catch (GetException e) {
            log.error("Can't get UserCalendar for calendar={} and user={}", calendarId, user.getKey());
            throw new RuntimeException(e);
        }
    }

    private void setCalendarFields(Calendar calendar, CalendarSettingDto calendarSettingDto) {
        calendar.setName(calendarSettingDto.getSelectedName());
        calendar.setSource(calendarSettingDto.getSelectedSourceId());
        calendar.setColor(calendarSettingDto.getSelectedColor());
        calendar.setEventStart(calendarSettingDto.getSelectedEventStartId());
        calendar.setEventEnd(calendarSettingDto.getSelectedEventEndId());
        calendar.setDisplayedFields(StringUtils.join(calendarSettingDto.getSelectedDisplayedFields(), ","));
        calendar.save();
    }

    private CalendarDto[] fillUserCalendarDtos(final ApplicationUser user, Calendar[] calendars) {
        List<CalendarDto> result = new ArrayList<CalendarDto>();
        Set<Integer> selectedCalendars = new TreeSet<Integer>();
        for (Calendar calendar : calendars) {
            boolean canAdmin = permissionService.hasAdminPermission(user, calendar);
            boolean canUse = canAdmin || permissionService.hasUsePermission(user, calendar);
            UserCalendar userCalendar = userCalendarService.find(calendar.getID(), user.getKey());
            if (canAdmin || canUse || userCalendar != null) {
                CalendarDto output = buildCalendarOutput(user, userCalendar, calendar, canUse, canAdmin, userCalendar != null && userCalendar.isEnabled(), userCalendar != null, userCalendarService.getUsersCount(calendar.getID()));
                if (!canAdmin && !canUse) {
                    output.setHasError(true);
                    output.setError(i18nHelper.getText("ru.mail.jira.plugins.calendar.unavailable"));
                }
                selectedCalendars.add(calendar.getID());
                result.add(output);
            }
        }
        for (UserCalendar userCalendar : userCalendarService.find(user.getKey())) {
            if (!selectedCalendars.contains(userCalendar.getCalendarId())) {
                CalendarDto output = buildCalendarOutput(user, userCalendar, null, false, false, false, true, 0);
                output.setHasError(true);
                output.setError(i18nHelper.getText("ru.mail.jira.plugins.calendar.unavailable"));
                result.add(output);
            }

        }
        return result.toArray(new CalendarDto[result.size()]);
    }

    private CalendarDto buildCalendarOutput(ApplicationUser user, UserCalendar userCalendar, Calendar calendar, boolean canUse, boolean changable, boolean visible, boolean favorite, int usersCount) {
        CalendarDto output = new CalendarDto(userCalendar, calendar);
        output.setViewable(canUse);
        output.setChangable(changable);
        output.setVisible(visible);
        output.setFavorite(favorite);
        output.setUsersCount(usersCount);

        if (calendar != null) {
            String filterHasNotAvailableError = checkThatFilterHasAvailable(user, calendar);
            if (filterHasNotAvailableError != null) {
                output.setHasError(true);
                output.setError(filterHasNotAvailableError);
            }
        }
        return output;
    }

    @Nullable
    private String checkThatFilterHasAvailable(ApplicationUser user, Calendar calendar) {
        if (calendar.getSource().startsWith("filter_")) {
            JiraServiceContext jiraServiceContext = new JiraServiceContextImpl(user);
            searchRequestService.getFilter(jiraServiceContext, Long.valueOf(calendar.getSource().substring("filter_".length())));
            return jiraServiceContext.getErrorCollection().hasAnyErrors() ? CommonUtils.formatErrorCollection(jiraServiceContext.getErrorCollection()) : null;
        }
        return null;
    }

    private void fillSelectedSourceFields(ApplicationUser user, CalendarSettingDto dto, Calendar calendar) {
        String source = calendar.getSource();
        dto.setSelectedSourceId(source);
        if (source.startsWith("project_")) {
            long projectId = Long.parseLong(source.substring("project_".length()));
            Project project = projectManager.getProjectObj(projectId);
            if (project == null || !permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, project, user, false)) {
                dto.setSelectedSourceIsUnavailable(true);
                dto.setSelectedSourceName(i18nHelper.getText("ru.mail.jira.plugins.calendar.unavailableSource"));
            } else {
                dto.setSelectedSourceName(String.format("%s (%s)", project.getName(), project.getKey()));
                dto.setSelectedSourceAvatarId(project.getAvatar().getId());
            }
        } else if (source.startsWith("filter_")) {
            long filterId = Long.parseLong(source.substring("filter_".length()));
            JiraServiceContext serviceContext = new JiraServiceContextImpl(user);
            SearchRequest filter = searchRequestService.getFilter(serviceContext, filterId);
            if (filter == null) {
                dto.setSelectedSourceIsUnavailable(true);
                dto.setSelectedSourceName(i18nHelper.getText("ru.mail.jira.plugins.calendar.unavailableSource"));
            } else
                dto.setSelectedSourceName(filter.getName());
        } else { // theoretically it isn't possible
            dto.setSelectedSourceName("Unknown source");
        }
    }

    private void validateCalendar(ApplicationUser user, CalendarSettingDto calendarSettingDto, boolean isCreate) {
        if (user == null)
            throw new IllegalArgumentException("User doesn't exist");
        if (StringUtils.isBlank(calendarSettingDto.getSelectedName()))
            throw new RestFieldException(i18nHelper.getText("issue.field.required", i18nHelper.getText("common.words.name")), "name");
        if (StringUtils.isBlank(calendarSettingDto.getSelectedSourceId()))
            throw new RestFieldException(i18nHelper.getText("issue.field.required", i18nHelper.getText("ru.mail.jira.plugins.calendar.dialog.source")), "source");
        if (StringUtils.isBlank(calendarSettingDto.getSelectedColor()))
            throw new RestFieldException(i18nHelper.getText("issue.field.required", i18nHelper.getText("admin.common.words.color")), "color");
        if (StringUtils.isBlank(calendarSettingDto.getSelectedEventStartId()))
            throw new RestFieldException(i18nHelper.getText("issue.field.required", i18nHelper.getText("ru.mail.jira.plugins.calendar.dialog.eventStart")), "event-start");

        if (!COLOR_PATTERN.matcher(calendarSettingDto.getSelectedColor()).matches())
            throw new IllegalArgumentException("Bad color => " + calendarSettingDto.getSelectedColor());

        if (!calendarSettingDto.getSelectedSourceId().startsWith("project_") && !calendarSettingDto.getSelectedSourceId().startsWith("filter_"))
            throw new IllegalArgumentException("Bad source => " + calendarSettingDto.getSelectedSourceId());

        try {
            if (calendarSettingDto.getSelectedSourceId().startsWith("project_")) {
                long projectId = Long.parseLong(calendarSettingDto.getSelectedSourceId().substring("project_".length()));
                if (isCreate) {
                    Project project = projectManager.getProjectObj(projectId);
                    if (project == null)
                        throw new RestFieldException("Can not find project with id => " + projectId, "source");

                    if (!permissionManager.hasPermission(ProjectPermissions.BROWSE_PROJECTS, project, user, false))
                        throw new RestFieldException("No Permission to browse project " + project.getName(), "source");
                }
            } else if (calendarSettingDto.getSelectedSourceId().startsWith("filter_")) {
                long filterId = Long.parseLong(calendarSettingDto.getSelectedSourceId().substring("filter_".length()));
                if (isCreate) {
                    if (searchRequestManager.getSearchRequestById(filterId) == null)
                        throw new RestFieldException("Can not find filter with id " + filterId, "source");

                    JiraServiceContext serviceContext = new JiraServiceContextImpl(user);
                    searchRequestService.getFilter(serviceContext, filterId);
                    if (serviceContext.getErrorCollection().hasAnyErrors())
                        throw new RestFieldException(serviceContext.getErrorCollection().getErrorMessages().toString(), "source");
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad source => " + calendarSettingDto.getSelectedSourceId());
        }

        for (String field : calendarSettingDto.getSelectedDisplayedFields())
            if (field.startsWith("customfield_")) {
                if (customFieldManager.getCustomFieldObject(field) == null)
                    throw new RestFieldException("Can not find custom field with id => " + field, "fields");
            } else if (!DISPLAYED_FIELDS.contains(field))
                throw new RestFieldException(String.format("Can not find field %s among standart fields", field), "fields");

        //todo check if permission subject exists
        //        if (StringUtils.isNotBlank(shares)) {
        //            for (String shareExpr : shares.split(";")) {
        //                LocalShare groupFromShare = getGroupFromExpr(shareExpr);
        //                LocalShare projectRoleFromShare = getProjectRoleFromExpr(user, shareExpr);
        //
        //                if (groupFromShare != null) {
        //                    if (!groupManager.groupExists(groupFromShare.groupName))
        //                        throw new RestFieldException(i18nHelper.getText("admin.viewgroup.group.does.not.exist"), "group_" + groupFromShare.groupName);
        //                    if (!isUserAdmin && !groupManager.isUserInGroup(user.getDirectoryUser().getName(), groupFromShare.groupName))
        //                        throw new RestFieldException(i18nHelper.getText("common.sharing.exception.not.in.group", groupFromShare.groupName), "group_" + groupFromShare.groupName);
        //                } else if (projectRoleFromShare != null) {
        //                    Project project = projectManager.getProjectObj(projectRoleFromShare.projectId);
        //                    if (project == null)
        //                        throw new RestFieldException(i18nHelper.getText("common.sharing.exception.project.does.not.exist"), "project_" + projectRoleFromShare.projectId);
        //
        //                    if (!isUserAdmin && !permissionManager.hasPermission(Permissions.BROWSE, project, user, false))
        //                        throw new RestFieldException(i18nHelper.getText("common.sharing.exception.no.permission.project", project.getName()),
        //                                                     "project_" + projectRoleFromShare.projectId);
        //
        //                    if (projectRoleFromShare.roleId != null) {
        //                        ProjectRole projectRole = projectRoleManager.getProjectRole(projectRoleFromShare.roleId);
        //                        if (projectRole == null)
        //                            throw new RestFieldException(i18nHelper.getText("admin.errors.specified.role.does.not.exist"), "project_role_" + projectRoleFromShare.roleId);
        //
        //                        if (!isUserAdmin && !projectRoleManager.isUserInProjectRole(user, projectRole, project))
        //                            throw new RestFieldException(i18nHelper.getText("common.sharing.exception.no.permission.role", project.getName(), projectRole.getName()), "project_role_" + projectRoleFromShare.roleId);
        //                    }
        //
        //                } else
        //                    throw new IllegalArgumentException("Bad shares value => " + shares);
        //            }
        //        }
    }
}
