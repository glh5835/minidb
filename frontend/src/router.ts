import { useEffect, useState } from 'react'

// 极简 hash 路由（不引入额外依赖）
export function useHashRoute(): string {
  const [route, setRoute] = useState(() => window.location.hash.slice(1) || '/start')
  useEffect(() => {
    const onChange = () => setRoute(window.location.hash.slice(1) || '/start')
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

export function nav(to: string) {
  window.location.hash = to
}
