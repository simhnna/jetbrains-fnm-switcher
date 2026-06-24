package eu.hannaweb.fnm.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object FnmNotifications {

    private const val GROUP_ID = "eu.hannaweb.fnm"

    fun notifyInfo(project: Project, title: String, content: String) {
        build(title, content, NotificationType.INFORMATION).notify(project)
    }

    fun notifyError(project: Project, title: String, content: String) {
        build(title, content, NotificationType.ERROR).notify(project)
    }

    /**
     * Creates a warning notification without showing it yet, so the caller
     * can attach actions before calling [Notification.notify].
     */
    fun buildWarning(title: String, content: String): Notification =
        build(title, content, NotificationType.WARNING)

    private fun build(title: String, content: String, type: NotificationType): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
}
