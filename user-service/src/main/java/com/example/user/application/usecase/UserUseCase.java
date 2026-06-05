package com.example.user.application.usecase;

import com.example.user.domain.entity.User;

public interface UserUseCase {
    record UpdateCommand(String name, String phone) {}
    record ChangePasswordCommand(String currentPassword, String newPassword) {}

    User getMe(long userId);
    User update(long userId, UpdateCommand command);
    void changePassword(long userId, ChangePasswordCommand command);
    void withdraw(long userId);
}
