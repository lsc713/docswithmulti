package com.example.user.application.usecase;

import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import java.util.List;

public interface AdminUseCase {
    List<User> getUsers();
    User updateStatus(long userId, UserStatus status);
    User updateRole(long userId, UserRole role);
}
