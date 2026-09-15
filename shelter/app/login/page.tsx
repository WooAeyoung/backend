'use client';
import { Suspense, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';

export default function LoginPage() {
  return <Suspense><LoginForm/></Suspense>;
}

function LoginForm() {
  const [key, setKey] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const router = useRouter();
  const params = useSearchParams();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key }) });
      if (!r.ok) throw new Error('접근 키가 올바르지 않습니다.');
      router.replace(params.get('next') || '/');
      router.refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: 'flex', minHeight: '100dvh', alignItems: 'center', justifyContent: 'center', padding: 16 }}>
      <form onSubmit={submit} style={{ display: 'grid', gap: 12, width: 320, padding: 24, border: '1px solid #ddd', borderRadius: 12 }}>
        <h1 style={{ fontSize: 18, margin: 0 }}>우애영 보호소</h1>
        <p style={{ margin: 0, color: '#666', fontSize: 13 }}>보호소 직원 전용 접근 키를 입력하세요.</p>
        {error && <div role="alert" style={{ color: '#c0392b', fontSize: 13 }}>{error}</div>}
        <input
          type="password"
          autoFocus
          required
          value={key}
          onChange={e => setKey(e.target.value)}
          placeholder="접근 키"
          style={{ padding: '8px 10px', border: '1px solid #ccc', borderRadius: 8 }}
        />
        <button disabled={busy} style={{ padding: '8px 10px', borderRadius: 8, border: 'none', background: '#2f8f7d', color: '#fff', fontWeight: 600 }}>
          {busy ? '확인 중…' : '입장'}
        </button>
      </form>
    </div>
  );
}
