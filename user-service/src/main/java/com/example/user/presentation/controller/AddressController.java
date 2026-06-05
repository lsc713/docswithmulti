package com.example.user.presentation.controller;

import com.example.user.application.usecase.AddressUseCase;
import com.example.user.application.usecase.AddressUseCase.*;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {
    private final AddressUseCase addressUseCase;

    @GetMapping
    public ResponseEntity<List<AddressResponse>> getAddresses(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(addressUseCase.getAddresses(userId).stream().map(AddressResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(Authentication authentication,
                                                   @RequestBody @Valid AddressRequest request) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(AddressResponse.from(addressUseCase.create(userId, new CreateCommand(
                request.label(), request.recipient(), request.phone(), request.zipCode(),
                request.address(), request.addressDetail(), request.isDefault()))));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AddressResponse> update(Authentication authentication, @PathVariable long id,
                                                   @RequestBody @Valid AddressRequest request) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(AddressResponse.from(addressUseCase.update(userId, id, new UpdateCommand(
                request.label(), request.recipient(), request.phone(), request.zipCode(),
                request.address(), request.addressDetail(), request.isDefault()))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id) {
        long userId = (long) authentication.getPrincipal();
        addressUseCase.delete(userId, id);
        return ResponseEntity.ok().build();
    }
}
