import Link from "next/link";

export interface Tab {
  href: string;
  label: string;
  count?: number;
  active: boolean;
}

export function Tabs({ tabs }: { tabs: Tab[] }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((tab) => (
        <Link key={tab.href} href={tab.href} role="tab" aria-selected={tab.active} className={tab.active ? "active" : undefined}>
          {tab.label}
          {tab.count !== undefined && <span className="tabcount">{tab.count}</span>}
        </Link>
      ))}
    </div>
  );
}
