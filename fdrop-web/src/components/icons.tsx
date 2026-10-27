import type { ReactNode } from "react";

/**
 * Line icons drawn in the text colour (currentColor), 24-unit grid, 2px stroke. Decorative: the label beside an icon
 * names it, and icon-only buttons carry their own aria-label.
 */
function Svg({ size = 18, children }: { size?: number; children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

type P = { size?: number };

export const HomeIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />
  </Svg>
);

/** A folder: the square-in-square mark the file list has always used for folders. */
export const FolderIcon = ({ size }: P) => (
  <Svg size={size}>
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <rect x="8" y="8" width="8" height="8" rx="1" />
  </Svg>
);

export const ForwardIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M4 12h14M13 6l6 6-6 6" />
  </Svg>
);

export const FileIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M6 2h9l5 5v15H6z" />
    <path d="M9 12h8M9 16h8" />
  </Svg>
);

export const ListIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M8 6h13M8 12h13M8 18h13M3 6h1M3 12h1M3 18h1" />
  </Svg>
);

/** A clipboard with lines: the audit log. */
export const AuditIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M9 4h6v3H9zM9 5.5H6a1 1 0 0 0-1 1V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V6.5a1 1 0 0 0-1-1h-3M8.5 12h7M8.5 16h5" />
  </Svg>
);

export const SettingsIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M4 7h10M18 7h2M4 17h4M12 17h8" />
    <circle cx="16" cy="7" r="2" />
    <circle cx="10" cy="17" r="2" />
  </Svg>
);

export const UserIcon = ({ size }: P) => (
  <Svg size={size}>
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21c1-4 4-6 8-6s7 2 8 6" />
  </Svg>
);

export const AppIcon = ({ size }: P) => (
  <Svg size={size}>
    <rect x="3" y="4" width="18" height="14" rx="2" />
    <path d="M8 21h8M7 9l3 2-3 2M13 13h4" />
  </Svg>
);

export const ServerIcon = ({ size }: P) => (
  <Svg size={size}>
    <rect x="3" y="4" width="18" height="7" rx="2" />
    <rect x="3" y="13" width="18" height="7" rx="2" />
    <path d="M7 7.5h.01M7 16.5h.01" />
  </Svg>
);

export const UploadIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M12 19V5M5 12l7-7 7 7" />
  </Svg>
);

export const PlusIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);

export const SearchIcon = ({ size }: P) => (
  <Svg size={size}>
    <circle cx="11" cy="11" r="7" />
    <path d="M20 20l-4-4" />
  </Svg>
);

export const MenuIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M4 6h16M4 12h16M4 18h16" />
  </Svg>
);

export const CloseIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M6 6l12 12M18 6L6 18" />
  </Svg>
);

export const HourglassIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M6 2h12M6 22h12M7 2c0 6 10 6 10 10S7 16 7 22M17 2c0 6-10 6-10 10s10 4 10 10" />
  </Svg>
);

export const SunIcon = ({ size }: P) => (
  <Svg size={size}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Svg>
);

export const MoonIcon = ({ size }: P) => (
  <Svg size={size}>
    <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" />
  </Svg>
);

export const LockIcon = ({ size }: P) => (
  <Svg size={size}>
    <rect x="4" y="11" width="16" height="10" rx="2" />
    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </Svg>
);
