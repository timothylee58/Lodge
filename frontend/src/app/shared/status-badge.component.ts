import { Component, computed, input } from '@angular/core';
import { ClaimStatus } from '../core/models';
import { STATUS_LABEL } from './format';

const TONE: Record<ClaimStatus, string> = {
  SUBMITTED: 'text-slate-600 border-slate-400 dark:text-slate-300',
  UNDER_REVIEW: 'text-blue-700 border-blue-500 dark:text-blue-300',
  PENDING_INFO: 'text-amber-700 border-amber-500 dark:text-amber-300',
  APPROVED: 'text-emerald-700 border-emerald-500 dark:text-emerald-300',
  SETTLED: 'text-slate-700 border-slate-500 dark:text-slate-200',
  REJECTED: 'text-red-700 border-red-500 dark:text-red-300',
};

@Component({
  selector: 'cd-status',
  standalone: true,
  template: `<span class="inline-block whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-semibold" [class]="tone()">{{ label() }}</span>`,
})
export class StatusBadgeComponent {
  status = input.required<ClaimStatus>();
  label = computed(() => STATUS_LABEL[this.status()]);
  tone = computed(() => 'inline-block whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-semibold ' + TONE[this.status()]);
}
