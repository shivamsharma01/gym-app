package com.example.gym.user.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AssignRolesRequest(@NotEmpty List<@NotBlank String> roles) {
}
