package com.example.merchantlimit.application.interfaces;

import com.example.merchantlimit.domain.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface MerchantRepository {
    Merchant save(Merchant merchant);
    Optional<Merchant> findById(long id);
    Optional<Merchant> findByMerchantKey(String merchantKey);
    boolean existsByMerchantKey(String merchantKey);
    Page<Merchant> findAll(Pageable pageable);
}
