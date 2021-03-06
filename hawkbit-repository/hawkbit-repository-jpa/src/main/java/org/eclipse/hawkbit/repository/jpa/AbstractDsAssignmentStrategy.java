/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.hawkbit.repository.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.hawkbit.repository.QuotaManagement;
import org.eclipse.hawkbit.repository.RepositoryConstants;
import org.eclipse.hawkbit.repository.event.remote.TargetAssignDistributionSetEvent;
import org.eclipse.hawkbit.repository.event.remote.entity.CancelTargetAssignmentEvent;
import org.eclipse.hawkbit.repository.event.remote.entity.TargetUpdatedEvent;
import org.eclipse.hawkbit.repository.jpa.configuration.Constants;
import org.eclipse.hawkbit.repository.jpa.executor.AfterTransactionCommitExecutor;
import org.eclipse.hawkbit.repository.jpa.model.JpaAction;
import org.eclipse.hawkbit.repository.jpa.model.JpaActionStatus;
import org.eclipse.hawkbit.repository.jpa.model.JpaDistributionSet;
import org.eclipse.hawkbit.repository.jpa.model.JpaTarget;
import org.eclipse.hawkbit.repository.jpa.utils.QuotaHelper;
import org.eclipse.hawkbit.repository.model.Action;
import org.eclipse.hawkbit.repository.model.Action.Status;
import org.eclipse.hawkbit.repository.model.DistributionSet;
import org.eclipse.hawkbit.repository.model.Target;
import org.eclipse.hawkbit.repository.model.TargetWithActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.CollectionUtils;

/**
 * {@link DistributionSet} to {@link Target} assignment strategy as utility for
 * {@link JpaDeploymentManagement}.
 *
 */
public abstract class AbstractDsAssignmentStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractDsAssignmentStrategy.class);

    protected final TargetRepository targetRepository;
    protected final AfterTransactionCommitExecutor afterCommit;
    protected final ApplicationEventPublisher eventPublisher;
    protected final ApplicationContext applicationContext;
    private final ActionRepository actionRepository;
    private final ActionStatusRepository actionStatusRepository;
    private final QuotaManagement quotaManagement;

    AbstractDsAssignmentStrategy(final TargetRepository targetRepository,
            final AfterTransactionCommitExecutor afterCommit, final ApplicationEventPublisher eventPublisher,
            final ApplicationContext applicationContext, final ActionRepository actionRepository,
            final ActionStatusRepository actionStatusRepository, final QuotaManagement quotaManagement) {
        this.targetRepository = targetRepository;
        this.afterCommit = afterCommit;
        this.eventPublisher = eventPublisher;
        this.applicationContext = applicationContext;
        this.actionRepository = actionRepository;
        this.actionStatusRepository = actionStatusRepository;
        this.quotaManagement = quotaManagement;
    }

    /**
     * Find targets to be considered for assignment.
     * 
     * @param controllerIDs
     *            as provided by repository caller
     * @param distributionSetId
     *            to assign
     * @return list of targets up to {@link Constants#MAX_ENTRIES_IN_STATEMENT}
     */
    abstract List<JpaTarget> findTargetsForAssignment(final List<String> controllerIDs, final long distributionSetId);

    /**
     * Update status and DS fields of given target.
     * 
     * @param distributionSet
     *            to set
     * @param targetIds
     *            to change
     * @param currentUser
     *            for auditing
     */
    abstract void updateTargetStatus(final JpaDistributionSet distributionSet, final List<List<Long>> targetIds,
            final String currentUser);

    /**
     * Cancels actions that can be canceled (i.e.
     * {@link DistributionSet#isRequiredMigrationStep() is <code>false</code>})
     * as a result of the new assignment and returns all {@link Target}s where
     * such actions existed.
     * 
     * @param targetIds
     *            to cancel actions for
     * @return {@link Set} of {@link Target#getId()}s
     */
    abstract Set<Long> cancelActiveActions(List<List<Long>> targetIds);

    /**
     * Cancels actions that can be canceled (i.e.
     * {@link DistributionSet#isRequiredMigrationStep() is <code>false</code>})
     * as a result of the new assignment and returns all {@link Target}s where
     * such actions existed.
     * 
     * @param targetIds
     *            to cancel actions for
     * @return {@link Set} of {@link Target#getId()}s
     */
    abstract void closeActiveActions(List<List<Long>> targetIds);

    /**
     * Handles event sending related to the assignment.
     * 
     * @param set
     *            that has been assigned
     * @param targets
     *            to send events for
     * @param targetIdsCancelList
     *            targets where an action was canceled
     * @param controllerIdsToActions
     *            mapping of {@link Target#getControllerId()} to new
     *            {@link Action} that was created as part of the assignment.
     */
    abstract void sendAssignmentEvents(DistributionSet set, final List<JpaTarget> targets,
            final Set<Long> targetIdsCancelList, final Map<String, JpaAction> controllerIdsToActions);

    protected void sendTargetAssignDistributionSetEvent(final String tenant, final long distributionSetId,
            final List<Action> actions) {
        if (CollectionUtils.isEmpty(actions)) {
            return;
        }

        afterCommit.afterCommit(
                () -> eventPublisher.publishEvent(new TargetAssignDistributionSetEvent(tenant, distributionSetId,
                        actions, applicationContext.getId(), actions.get(0).isMaintenanceWindowAvailable())));
    }

    protected void sendTargetUpdatedEvent(final JpaTarget target) {
        afterCommit.afterCommit(
                () -> eventPublisher.publishEvent(new TargetUpdatedEvent(target, applicationContext.getId())));
    }

    /**
     * Cancels {@link Action}s that are no longer necessary and sends
     * cancellations to the controller.
     *
     * @param targetsIds
     *            to override {@link Action}s
     */
    protected List<Long> overrideObsoleteUpdateActions(final Collection<Long> targetsIds) {

        // Figure out if there are potential target/action combinations that
        // need to be considered for cancellation
        final List<JpaAction> activeActions = actionRepository
                .findByActiveAndTargetIdInAndActionStatusNotEqualToAndDistributionSetNotRequiredMigrationStep(
                        targetsIds, Action.Status.CANCELING);

        return activeActions.stream().map(action -> {
            action.setStatus(Status.CANCELING);

            // document that the status has been retrieved
            actionStatusRepository.save(new JpaActionStatus(action, Status.CANCELING, System.currentTimeMillis(),
                    RepositoryConstants.SERVER_MESSAGE_PREFIX + "cancel obsolete action due to new update"));
            actionRepository.save(action);

            cancelAssignDistributionSetEvent(action.getTarget(), action.getId());

            return action.getTarget().getId();
        }).collect(Collectors.toList());

    }

    /**
     * Closes {@link Action}s that are no longer necessary without sending a
     * hint to the controller.
     *
     * @param targetsIds
     *            to override {@link Action}s
     */
    protected List<Long> closeObsoleteUpdateActions(final Collection<Long> targetsIds) {

        // Figure out if there are potential target/action combinations that
        // need to be considered for cancellation
        final List<JpaAction> activeActions = actionRepository
                .findByActiveAndTargetIdInAndDistributionSetNotRequiredMigrationStep(targetsIds);

        return activeActions.stream().map(action -> {
            action.setStatus(Status.CANCELED);
            action.setActive(false);

            // document that the status has been retrieved
            actionStatusRepository.save(new JpaActionStatus(action, Status.CANCELED, System.currentTimeMillis(),
                    RepositoryConstants.SERVER_MESSAGE_PREFIX + "close obsolete action due to new update"));
            actionRepository.save(action);

            return action.getTarget().getId();
        }).collect(Collectors.toList());

    }

    /**
     * Sends the {@link CancelTargetAssignmentEvent} for a specific target to
     * the eventPublisher.
     *
     * @param target
     *            the Target which has been assigned to a distribution set
     * @param actionId
     *            the action id of the assignment
     */
    void cancelAssignDistributionSetEvent(final Target target, final Long actionId) {
        afterCommit.afterCommit(() -> eventPublisher
                .publishEvent(new CancelTargetAssignmentEvent(target, actionId, applicationContext.getId())));
    }

    JpaAction createTargetAction(final Map<String, TargetWithActionType> targetsWithActionMap, final JpaTarget target,
            final JpaDistributionSet set) {

        // enforce the 'max actions per target' quota
        assertActionsPerTargetQuota(target, 1);

        // create the action
        return getTargetWithActionType(targetsWithActionMap, target.getControllerId()).map(targetWithActionType -> {
            final JpaAction actionForTarget = new JpaAction();
            actionForTarget.setActionType(targetWithActionType.getActionType());
            actionForTarget.setForcedTime(targetWithActionType.getForceTime());
            actionForTarget.setActive(true);
            actionForTarget.setTarget(target);
            actionForTarget.setDistributionSet(set);
            actionForTarget.setMaintenanceWindowSchedule(targetWithActionType.getMaintenanceSchedule());
            actionForTarget.setMaintenanceWindowDuration(targetWithActionType.getMaintenanceWindowDuration());
            actionForTarget.setMaintenanceWindowTimeZone(targetWithActionType.getMaintenanceWindowTimeZone());
            return actionForTarget;
        }).orElseGet(() -> {
            LOG.warn("Cannot find targetWithActionType for target '{}'.", target.getControllerId());
            return null;
        });
    }

    JpaActionStatus createActionStatus(final JpaAction action, final String actionMessage) {
        final JpaActionStatus actionStatus = new JpaActionStatus();
        actionStatus.setAction(action);
        actionStatus.setOccurredAt(action.getCreatedAt());

        if (actionMessage != null) {
            actionStatus.addMessage(actionMessage);
        }

        return actionStatus;
    }

    private void assertActionsPerTargetQuota(final Target target, final int requested) {
        final int quota = quotaManagement.getMaxActionsPerTarget();
        QuotaHelper.assertAssignmentQuota(target.getId(), requested, quota, Action.class, Target.class,
                actionRepository::countByTargetId);
    }

    private static Optional<TargetWithActionType> getTargetWithActionType(
            final Map<String, TargetWithActionType> targetsWithActionMap, final String controllerId) {
        if (targetsWithActionMap.containsKey(controllerId)) {
            return Optional.of(targetsWithActionMap.get(controllerId));
        } else {
            return targetsWithActionMap.values().stream()
                    .filter(t -> controllerId.equalsIgnoreCase(t.getControllerId())).findFirst();
        }
    }

}
