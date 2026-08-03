package com.example.product.application.service;

import com.example.product.application.interfaces.AttributeRepository;
import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.common.exception.application.DescriptiveValueInvalidException;
import com.example.product.common.exception.application.ProductCategoryInvalidException;
import com.example.product.common.exception.application.VariantCombinationDuplicateException;
import com.example.product.common.exception.application.VariantIncompleteException;
import com.example.product.common.exception.application.VariantValueInvalidException;
import com.example.product.domain.entity.Category;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductAttribute;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 최소 등록(seed): product + 변형 속성 선언 + SKU + 초기재고를 한 TX로 저장 (STOCK-02, PLINK-01, VAR-01). */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductStockRepository stockRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository variantRepository;
    private final AttributeRepository attributeRepository;

    public CatalogService(ProductRepository productRepository,
                          ProductSkuRepository skuRepository,
                          ProductStockRepository stockRepository,
                          CategoryRepository categoryRepository,
                          ProductVariantRepository variantRepository,
                          AttributeRepository attributeRepository) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.stockRepository = stockRepository;
        this.categoryRepository = categoryRepository;
        this.variantRepository = variantRepository;
        this.attributeRepository = attributeRepository;
    }

    /** 상품이 선언하는 속성 + 역할(변형/서술). */
    public record ProductAttributeSeed(Long attributeId, boolean isVariant) {}

    public record SkuSeed(String skuCode, String optionSummary, int initialStock, long price,
                          List<Long> variantValueIds) {
        /** 하위호환: 변형 없는 기존 등록 경로. */
        public SkuSeed(String skuCode, String optionSummary, int initialStock, long price) {
            this(skuCode, optionSummary, initialStock, price, List.of());
        }
    }

    public record SeededSku(Long skuId, String skuCode) {}

    public record SeedResult(Long productId, List<SeededSku> skus) {}

    /** 하위호환 오버로드: 변형 선언 없는 기존 등록. */
    @Transactional
    public SeedResult seed(String name, Long categoryId, List<SkuSeed> skus) {
        return seed(name, categoryId, List.of(), skus, List.of());
    }

    /** 하위호환 오버로드: 서술값 없는 등록. */
    @Transactional
    public SeedResult seed(String name, Long categoryId, List<ProductAttributeSeed> attributes, List<SkuSeed> skus) {
        return seed(name, categoryId, attributes, skus, List.of());
    }

    @Transactional
    public SeedResult seed(String name, Long categoryId, List<ProductAttributeSeed> attributes,
                           List<SkuSeed> skus, List<Long> descriptiveValueIds) {
        // PLINK-01: categoryId 는 존재하는 leaf(level 3)여야 한다. 부재/비-leaf 모두 400 PRODUCT_001.
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ProductCategoryInvalidException(categoryId));
        if (!category.isLeaf()) {
            throw new ProductCategoryInvalidException(categoryId);
        }

        // 변형 검증(persist 전 fail-fast, 예외 시 TX 롤백). 소속→완전성→유일성 순 (VAR-02).
        validateVariants(attributes, skus);

        // 서술 소속 검증(persist 전 fail-fast). 중복 제거로 복합키 충돌 회피.
        List<Long> distinctDescriptive = descriptiveValueIds.stream().distinct().toList();
        validateDescriptiveMembership(attributes, distinctDescriptive);

        Product product = productRepository.save(Product.create(name, categoryId));

        // 변형/서술 속성 선언 저장 (Task 3 에서 저장 전 검증 추가; tracer 는 배선만).
        if (!attributes.isEmpty()) {
            variantRepository.saveProductAttributes(product.getId(), attributes.stream()
                    .map(a -> ProductAttribute.create(product.getId(), a.attributeId(), a.isVariant()))
                    .toList());
        }

        List<SeededSku> seeded = skus.stream().map(s -> {
            ProductSku sku = skuRepository.save(
                    ProductSku.create(product.getId(), s.skuCode(), s.optionSummary(), s.price()));
            stockRepository.save(ProductStock.create(sku.getId(), s.initialStock())); // INV-01: 재고 저장 무변경
            if (!s.variantValueIds().isEmpty()) {
                variantRepository.saveSkuVariantValues(sku.getId(), s.variantValueIds());
            }
            return new SeededSku(sku.getId(), sku.getSkuCode());
        }).toList();

        // 서술값 저장(VAR-03): 상품 레벨 다속성·다값. 재고 경로 무변경(INV-01).
        if (!distinctDescriptive.isEmpty()) {
            variantRepository.saveProductDescriptiveValues(product.getId(), distinctDescriptive);
        }
        return new SeedResult(product.getId(), seeded);
    }

    /**
     * 서술 소속 검증 (VAR-03). descriptiveValueIds 각 값은 그 상품이 서술로 선언한(is_variant=false)
     * 속성 소속이어야 한다 — 미존재 값이거나 변형/미선언 속성 소속이면 400 DESCRIPTIVE_001.
     * 완전성·유일성은 미강제(태그 의미).
     */
    private void validateDescriptiveMembership(List<ProductAttributeSeed> attributes, List<Long> descriptiveValueIds) {
        if (descriptiveValueIds.isEmpty()) {
            return;
        }
        Set<Long> declaredDescriptiveAttrIds = attributes.stream()
                .filter(a -> !a.isVariant())
                .map(ProductAttributeSeed::attributeId)
                .collect(Collectors.toSet());
        Map<Long, Long> attrByValue = attributeRepository.findAttributeIdByValueIds(descriptiveValueIds);
        for (Long vid : descriptiveValueIds) {
            Long attrId = attrByValue.get(vid);
            if (attrId == null || !declaredDescriptiveAttrIds.contains(attrId)) {
                throw new DescriptiveValueInvalidException();
            }
        }
    }

    /**
     * 변형 조합 검증 (VAR-02). 선언 속성 존재 → 소속(VARIANT_003) → 완전성(VARIANT_001) → 유일성(VARIANT_002).
     * combo_hash 컬럼 없이 앱 레벨 정렬 집합 비교 — seed 가 유일한 SKU 생성 경로라 TOCTOU 없음(design_decisions).
     */
    private void validateVariants(List<ProductAttributeSeed> attributes, List<SkuSeed> skus) {
        // 선언 속성 전부 존재 (미존재 → 400 VARIANT_003).
        for (ProductAttributeSeed a : attributes) {
            if (!attributeRepository.existsAttribute(a.attributeId())) {
                throw new VariantValueInvalidException();
            }
        }
        Set<Long> declaredVariantAttrIds = attributes.stream()
                .filter(ProductAttributeSeed::isVariant)
                .map(ProductAttributeSeed::attributeId)
                .collect(Collectors.toSet());

        // 변형 선언이 없는 등록(하위호환): SKU 에 변형값이 있으면 소속 위반, 없으면 검증 스킵.
        if (declaredVariantAttrIds.isEmpty()) {
            if (skus.stream().anyMatch(s -> !s.variantValueIds().isEmpty())) {
                throw new VariantValueInvalidException();
            }
            return;
        }

        List<Long> allValueIds = skus.stream()
                .flatMap(s -> s.variantValueIds().stream())
                .toList();
        Map<Long, Long> attrByValue = attributeRepository.findAttributeIdByValueIds(allValueIds);

        List<Set<Long>> seenCombos = new ArrayList<>();
        for (SkuSeed s : skus) {
            List<Long> vids = s.variantValueIds();

            // 소속(VARIANT_003): 값이 존재하고 그 속성이 선언된 변형 속성이어야 함.
            for (Long vid : vids) {
                Long attrId = attrByValue.get(vid);
                if (attrId == null || !declaredVariantAttrIds.contains(attrId)) {
                    throw new VariantValueInvalidException();
                }
            }

            // 완전성(VARIANT_001): 속성별 그룹 — 선언 변형 속성 집합과 정확히 일치 AND 각 속성 값 1개.
            Map<Long, Integer> countByAttr = new HashMap<>();
            for (Long vid : vids) {
                countByAttr.merge(attrByValue.get(vid), 1, Integer::sum);
            }
            boolean coversExactly = countByAttr.keySet().equals(declaredVariantAttrIds);
            boolean oneEach = countByAttr.values().stream().allMatch(c -> c == 1);
            if (!coversExactly || !oneEach) {
                throw new VariantIncompleteException();
            }

            // 유일성(VARIANT_002): 정렬된 value-id 집합을 상품 내에서 비교.
            Set<Long> combo = new TreeSet<>(vids);
            if (seenCombos.contains(combo)) {
                throw new VariantCombinationDuplicateException();
            }
            seenCombos.add(combo);
        }
    }
}
