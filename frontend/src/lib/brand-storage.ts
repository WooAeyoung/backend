/** Move device preferences before React reads them; never overwrite newer data. */
export function migrateBrandStorage(storage: Storage): void {
  for (const suffix of ['api-base', 'token', 'goals', 'onboarded', 'theme', 'cart', 'diet']) {
    const oldKey = `pb-${suffix}`;
    const newKey = `wooaeyoung-${suffix}`;
    try {
      const value = storage.getItem(oldKey);
      if (value === null) continue;
      if (storage.getItem(newKey) === null) storage.setItem(newKey, value);
      storage.removeItem(oldKey);
    } catch {
      // Leave the original value intact if storage is full or unavailable.
    }
  }
}
