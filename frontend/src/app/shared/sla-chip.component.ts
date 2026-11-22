import { Component, computed, input } from '@angular/core';
import { ClaimStatus, SlaState } from '../core/models';

/** SLA against the target for the claim's CURRENT status. While waiting on the claimant, a breach means "chase". */
@Component({
  selector: 'lg-sla',
  standalone: true,
  template: `@if (label(); as l) { <span [class]="tone()" [attr.title]="title()">{{ l }}</span> }`,
})
export class SlaChipComponent {
  sla = input<SlaState | null>(null);
  status = input.required<ClaimStatus>();
  dueAt = input<string | null>(null);

  label = computed(() => {
    switch (this.sla()) {
      case 'BREACHED': return this.status() === 'PENDING_INFO' ? 'Chase claimant' : 'Overdue';
      case 'DUE_SOON': return 'Due soon';
      case 'ON_TRACK': return 'On track';
      default: return null;
    }
  });
  tone = computed(() => 'inline-block whitespace-nowrap rounded-md border px-2 py-0.5 text-xs font-semibold ' + ({
    BREACHED: 'border-red-500 text-red-700 dark:text-red-300',
    DUE_SOON: 'border-amber-500 text-amber-700 dark:text-amber-300',
    ON_TRACK: 'border-emerald-500 text-emerald-700 dark:text-emerald-300',
    NONE: '',
  } as const)[this.sla() ?? 'NONE']);
  title = computed(() => this.dueAt() ? `Target: ${new Date(this.dueAt()!).toLocaleString()}` : '');
}
