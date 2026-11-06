import type { SVGProps } from 'react'

const shapes = {
  database: <><ellipse cx="12" cy="5" rx="8" ry="3" /><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" /></>,
  home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /><path d="M9 21v-7h6v7" /></>,
  grid: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 9h18M3 14h18M10 9v11" /></>,
  terminal: <><rect x="2.5" y="4" width="19" height="16" rx="2.5" /><path d="m7 9 3 3-3 3m6 0h4" /></>,
  tree: <><rect x="9" y="3" width="6" height="5" rx="1" /><rect x="3" y="16" width="6" height="5" rx="1" /><rect x="15" y="16" width="6" height="5" rx="1" /><path d="M12 8v4m-6 4v-4h12v4" /></>,
  layers: <path d="m12 3 9 5-9 5-9-5 9-5zm-9 9 9 5 9-5m-18 4 9 5 9-5" />,
  flask: <path d="M9 3h6m-5 0v7L5 18a2 2 0 0 0 1.7 3h10.6a2 2 0 0 0 1.7-3l-5-8V3m-6 12h8" />,
  activity: <path d="M2 12h5l3-7 4 14 3-7h5" />,
  lock: <><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4m-4 4v2" /></>,
  rotate: <path d="M3 12a9 9 0 1 1 2.5 6.2M3 5v7h7m2-5v5l3 2" />,
  gauge: <><path d="M5 19a9 9 0 1 1 14 0m-7-5 4-4" /><circle cx="12" cy="14" r="1.5" /></>,
  plug: <path d="M8 3v5m8-5v5M6 8h12v2a6 6 0 0 1-12 0zm6 8v5" />,
  book: <path d="M12 7c-2-3-6-3-9-2v14c3-1 7-1 9 2 2-3 6-3 9-2V5c-3-1-7-1-9 2zm0 0v14" />,
  chip: <><rect x="6" y="6" width="12" height="12" rx="2" /><path d="M9 2v4m6-4v4m-6 12v4m6-4v4M2 9h4m-4 6h4m12-6h4m-4 6h4" /><rect x="9" y="9" width="6" height="6" rx="1" /></>,
  chevron: <path d="m9 6 6 6-6 6" />,
  'arrow-right': <path d="M4 12h15m-6-6 6 6-6 6" />,
  plus: <path d="M12 5v14M5 12h14" />,
  folder: <path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />,
  file: <path d="M6 3h8l4 4v14H6zM14 3v5h4m-9 5h6m-6 4h6" />,
  school: <path d="m2 9 10-5 10 5-10 5-10-5zm4 2v6c3 3 9 3 12 0v-6m4-2v6" />,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  play: <path d="m8 5 12 7-12 7z" />,
  refresh: <path d="M20 11a8 8 0 0 0-14-5L3 9m0-5v5h5m-4 4a8 8 0 0 0 14 5l3-3m0 5v-5h-5" />,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v5m0-8v.2" /></>,
  scan: <path d="M9 3H5a2 2 0 0 0-2 2v4m12-6h4a2 2 0 0 1 2 2v4m0 6v4a2 2 0 0 1-2 2h-4m-6 0H5a2 2 0 0 1-2-2v-4m3-3h12" />,
  search: <><circle cx="10.5" cy="10.5" r="5.5" /><path d="m15 15 5 5" /></>,
  key: <><circle cx="8" cy="9" r="4" /><path d="m11 12 8 8m-3-3 2-2m-5-1 2-2" /></>,
  trash: <path d="M4 6h16M9 6V3h6v3M6 6l1 14h10l1-14M10 10v6m4-6v6" />,
  edit: <path d="M5 19l4-.8L20 7l-3-3L6 15zM14.5 6.5l3 3" />,
  download: <path d="M12 3v12m-4-4 4 4 4-4M4 16v4h16v-4" />,
  check: <path d="m5 12 4 4L19 6" />,
  dot: <circle cx="12" cy="12" r="3" fill="currentColor" stroke="none" />,
  'circle-help': <><circle cx="12" cy="12" r="9" /><path d="M9.1 9a3 3 0 1 1 4.4 2.6c-1.4.8-1.5 1.4-1.5 2.4m0 3v.2" /></>,
  x: <path d="m6 6 12 12M18 6 6 18" />,
  sidebar: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></>,
}

export type StudioIconName = keyof typeof shapes

export function StudioIcon({ name, className, ...props }: SVGProps<SVGSVGElement> & { name: StudioIconName }) {
  return (
    <svg
      className={className ? `studio-icon ${className}` : 'studio-icon'}
      viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...props}
    >
      {shapes[name]}
    </svg>
  )
}
