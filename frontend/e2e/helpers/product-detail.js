function isProductDetailGet(response) {
  const url = new URL(response.url())
  return response.request().method() === 'GET' && /^\/v1\/products\/[^/]+$/.test(url.pathname)
}

function skuSelections(product, sku) {
  if (product.variantOptions?.length) {
    return product.variantOptions.map(({ attribute }) => {
      const value = sku.variant?.[attribute]
      if (value === undefined) {
        throw new Error(`SKU ${sku.skuId}에 ${attribute} 옵션 값이 없습니다.`)
      }
      return { attribute, value }
    })
  }

  return [{ attribute: '옵션', value: sku.optionSummary }]
}

export async function openProductDetail(page, productCard) {
  const responsePromise = page.waitForResponse(isProductDetailGet)
  await productCard.click()
  const response = await responsePromise

  if (!response.ok()) {
    throw new Error(`상품 상세 GET이 ${response.status()} 응답을 반환했습니다.`)
  }

  const product = await response.json()
  const detail = page.getByRole('main', { name: '상품 상세' })
  await detail.waitFor({ state: 'visible' })
  return { detail, product }
}

export async function openFirstInStockProductDetail(page, productCard, quantity = 1) {
  const { detail, product } = await openProductDetail(page, productCard)
  const sku = product.skus?.find(candidate => candidate.availableQty > 0)
  if (!sku) throw new Error(`재고가 있는 SKU가 없습니다: ${product.name}`)

  for (const { attribute, value } of skuSelections(product, sku)) {
    await detail.getByRole('button', { name: `${attribute} · 선택 안 됨` }).click()
    const listbox = detail.getByRole('listbox', { name: `${attribute} 옵션` })
    await listbox.getByRole('option', { name: value, exact: true }).click()
  }

  await detail.getByRole('spinbutton', { name: '수량' }).fill(String(quantity))
  return { detail, product, sku }
}
