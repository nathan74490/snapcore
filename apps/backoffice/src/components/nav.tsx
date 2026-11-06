"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

interface Item {
  href: string;
  label: string;
  badge?: number;
}

// Navigation latérale : le lien de la section ouverte est mis en évidence.
export function Nav({ sections }: { sections: { title: string; items: Item[] }[] }) {
  const pathname = usePathname();
  const active = (href: string) => (href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`));
  return (
    <nav className="sidenav" aria-label="Navigation principale">
      {sections.map((section) => (
        <div key={section.title} className="navsection">
          <div className="navtitle">{section.title}</div>
          {section.items.map((item) => (
            <Link key={item.href} href={item.href} className={active(item.href) ? "active" : undefined} aria-current={active(item.href) ? "page" : undefined}>
              <span>{item.label}</span>
              {item.badge ? <span className="count">{item.badge}</span> : null}
            </Link>
          ))}
        </div>
      ))}
    </nav>
  );
}
