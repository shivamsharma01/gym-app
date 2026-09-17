package com.example.gym.notification.whatsapp;

import java.util.List;

public record WhatsAppComponent(
	    String type,
	    List<WhatsAppParameter> parameters
	) {}