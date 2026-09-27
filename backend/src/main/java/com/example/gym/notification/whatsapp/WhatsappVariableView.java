package com.example.gym.notification.whatsapp;

import com.example.gym.notification.template.NotificationTemplateVariable;

public record WhatsappVariableView(
        WhatsappVariable variable,
        Integer order
) {

    public static WhatsappVariableView from(
            NotificationTemplateVariable variable) {

        return new WhatsappVariableView(
            variable.getVariableName(),
            variable.getVariableOrder()
        );
    }
}
