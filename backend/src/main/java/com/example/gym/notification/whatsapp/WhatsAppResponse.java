package com.example.gym.notification.whatsapp;

import java.util.List;

public record WhatsAppResponse(String messaging_product, List<Contact> contacts, List<Message> messages) {

	public record Contact(String input, String wa_id) {
	}

	public record Message(String id) {
	}
}