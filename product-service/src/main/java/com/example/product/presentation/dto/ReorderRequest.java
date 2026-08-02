package com.example.product.presentation.dto;

import java.util.List;

public record ReorderRequest(List<Long> imageIds) {}
