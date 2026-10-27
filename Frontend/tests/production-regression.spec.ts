import { expect, test } from '@playwright/test'

test.describe('production regression', () => {
  test.use({ viewport: { width: 1440, height: 900 }, reducedMotion: 'no-preference' })

  test('home restores its pinned ScrollTrigger story after reload', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('.story-stage')).toBeVisible()
    await expect.poll(() => page.locator('.pin-spacer').count()).toBeGreaterThan(0)

    await page.mouse.wheel(0, 2300)
    await expect.poll(() => page.locator('.story-root').getAttribute('data-step')).toBe('02')
    await expect(page.locator('.story-stage')).toHaveCSS('position', 'fixed')

    await page.reload()
    await expect.poll(() => page.locator('.pin-spacer').count()).toBeGreaterThan(0)
  })

  test('pet can move, pause, hide, and reappear', async ({ page }) => {
    await page.goto('/')
    const pet = page.locator('.roaming-pet')
    await expect(pet).toBeVisible()
    const initialLeft = await pet.evaluate(element => getComputedStyle(element).left)
    await page.waitForTimeout(900)
    await expect.poll(() => pet.evaluate(element => getComputedStyle(element).left)).not.toBe(initialLeft)

    await page.getByRole('button', { name: '펫 설정' }).click()
    await page.getByRole('button', { name: '걷기 멈추기' }).click()
    await page.getByRole('button', { name: '펫 설정' }).click()
    await expect(page.getByRole('button', { name: '걷기 재개' })).toBeVisible()
    await page.getByRole('button', { name: '펫 숨기기' }).click()
    await expect(page.getByRole('button', { name: '🐾 펫 보이기' })).toBeVisible()
    await page.getByRole('button', { name: '🐾 펫 보이기' }).click()
    await expect(pet).toBeVisible()
  })

  test('supplement analysis uses the live NIAS v3 API result', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '영양제 분석' }).click()
    await page.getByRole('textbox', { name: '이름' }).fill('회귀테스트')
    await page.getByRole('spinbutton', { name: '체중(kg)' }).fill('10')
    await page.getByRole('spinbutton', { name: '나이' }).fill('36')
    await page.getByRole('button', { name: '추가' }).first().click()

    const [response] = await Promise.all([
      page.waitForResponse(candidate => candidate.url().includes('/api/v1/analyses') && candidate.request().method() === 'POST'),
      page.getByRole('button', { name: '맞춤 분석 시작하기' }).click(),
    ])
    expect(response.status()).toBe(200)
    const body = await response.json()
    expect(body.standardVersion).toBe('NIAS-v3')
    await expect(page.getByText('결과 18 종 성분')).toBeVisible()
  })

  test('mobile home keeps the story and pet controls visible', async ({ browser }) => {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, reducedMotion: 'no-preference' })
    await page.goto('/')
    await expect(page.locator('.story-stage')).toBeVisible()
    await expect(page.getByRole('button', { name: '펫 설정' })).toBeVisible()
    await page.screenshot({ path: 'test-results/mobile-home.png' })
    await page.close()
  })
})
