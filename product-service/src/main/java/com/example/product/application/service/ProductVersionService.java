package com.example.product.application.service;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.application.usecase.ProductVersionUseCase;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.ProductVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductVersionService implements ProductVersionUseCase {

    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;

    @Override
    @Transactional
    public ProductVersion createVersion(long productId, Command command) {
        // Validate product exists
        productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        // Mark current version as not current
        productVersionRepository.findCurrentByProductId(productId).ifPresent(current -> {
            current.markNotCurrent();
            productVersionRepository.save(current);
        });

        // Find the latest version to determine next version number
        List<ProductVersion> versions = productVersionRepository.findAllByProductId(productId);
        ProductVersion previousVersion = versions.isEmpty() ? null : versions.get(0);

        ProductVersion newVersion;
        if (previousVersion == null) {
            newVersion = ProductVersion.createFirst(productId, command.name(), command.price(),
                    command.discountPrice(), command.attributes());
        } else {
            newVersion = ProductVersion.createNext(productId, command.name(), command.price(),
                    command.discountPrice(), command.attributes(), previousVersion);
        }

        return productVersionRepository.save(newVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductVersion> getVersions(long productId) {
        return productVersionRepository.findAllByProductId(productId);
    }
}
