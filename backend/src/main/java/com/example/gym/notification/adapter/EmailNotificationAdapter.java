package com.example.gym.notification.adapter;
//package com.example.gym.notification;
//
//import org.springframework.stereotype.Component;
//
//@Component
//public class EmailNotificationAdapter
//        implements NotificationChannelAdapter {
//
//    private final ExistingEmailService emailService;
//
//    public EmailNotificationAdapter(
//            ExistingEmailService emailService) {
//
//        this.emailService = emailService;
//    }
//
//    @Override
//    public NotificationChannel channel() {
//        return NotificationChannel.EMAIL;
//    }
//
//    @Override
//    public void send(OutboundNotification notification) {
//
//        emailService.send(
//                notification.getRecipient(),
//                notification.getSubject(),
//                notification.getBody());
//    }
//}