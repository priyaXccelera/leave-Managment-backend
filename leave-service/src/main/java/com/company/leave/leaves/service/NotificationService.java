package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.leaves.domain.Notification;
import com.company.leave.leaves.dto.MasterDtos.NotificationDto;
import com.company.leave.leaves.mapper.MasterMapper;
import com.company.leave.leaves.repo.NotificationRepository;
import com.company.leave.leaves.web.PageRequests;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class NotificationService {

    static final Set<String> SORT_FIELDS = Set.of("createdAt", "id", "read");
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** Stores one in-app notification; a null recipient is ignored. */
    @Transactional
    public void notify(Long recipientId, String message, Long requestId) {
        if (recipientId == null) {
            return;
        }
        Notification n = new Notification();
        n.setRecipientId(recipientId);
        n.setMessage(message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message);
        n.setRequestId(requestId);
        notifications.save(n);
    }

    /** Notifies every distinct recipient once. */
    @Transactional
    public void notifyAll(Collection<Long> recipientIds, String message, Long requestId) {
        if (recipientIds == null) {
            return;
        }
        for (Long id : new LinkedHashSet<>(recipientIds)) {
            notify(id, message, requestId);
        }
    }

    /** Newest first by default; {@code sort} accepts createdAt, id, read. */
    @Transactional(readOnly = true)
    public Page<NotificationDto> list(Long userId, boolean unreadOnly, Integer page, Integer pageSize) {
        Pageable pageable = PageRequests.of(page, pageSize, null, SORT_FIELDS, "createdAt,desc");
        Page<Notification> result = unreadOnly
                ? notifications.findByRecipientIdAndReadFalse(userId, pageable)
                : notifications.findByRecipientId(userId, pageable);
        return result.map(MasterMapper::toDto);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByRecipientIdAndReadFalse(userId);
    }

    /** @throws ApiException 404 when the notification does not exist or belongs to someone else */
    @Transactional
    public NotificationDto markRead(Long userId, Long notificationId) {
        Notification n = notifications.findById(notificationId)
                .filter(x -> x.getRecipientId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        n.setRead(true);
        return MasterMapper.toDto(notifications.save(n));
    }

    /** @return number of notifications that were unread */
    @Transactional
    public int markAllRead(Long userId) {
        return notifications.markAllRead(userId);
    }
}
