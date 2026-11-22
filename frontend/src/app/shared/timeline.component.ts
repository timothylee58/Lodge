import { Component, input } from '@angular/core';
import { TimelineEntry } from '../core/models';
import { EVENT_LABEL, money, when } from './format';

@Component({
  selector: 'cd-timeline',
  standalone: true,
  template: `
    <ol class="m-0 list-none border-l-2 border-slate-200 p-0 dark:border-slate-700">
      @for (e of entries(); track $index) {
        <li class="relative pb-4 pl-4 before:absolute before:-left-[6px] before:top-2 before:h-2.5 before:w-2.5 before:rounded-full before:bg-blue-600 before:content-['']">
          <div class="flex flex-wrap justify-between gap-2"><strong>{{ label(e.type) }}</strong><time class="text-sm text-slate-500">{{ when(e.at) }}</time></div>
          @if (text(e); as t) { <p class="m-0 mt-0.5 text-slate-500 dark:text-slate-400">{{ t }}</p> }
        </li>
      } @empty { <li class="text-slate-500">No activity yet.</li> }
    </ol>`,
})
export class TimelineComponent {
  entries = input.required<TimelineEntry[]>();
  currency = input<string>('MYR');
  when = when;

  label(type: string) { return EVENT_LABEL[type] ?? type; }

  text(e: TimelineEntry): string | null {
    const d = e.detail ?? {};
    const c = this.currency();
    if (typeof d['question'] === 'string') return `“${d['question']}”`;
    if (typeof d['response'] === 'string') return `“${d['response']}”`;
    if (typeof d['reason'] === 'string') return `Reason: ${d['reason']}`;
    if (d['settledAmount'] != null) return `Settled for ${money(Number(d['settledAmount']), c)}`;
    if (d['reserveAmount'] != null) return `Reserve set to ${money(Number(d['reserveAmount']), c)}`;
    if (d['to'] != null) return `${money(Number(d['from']), c)} → ${money(Number(d['to']), c)}`;
    if (typeof d['policyNumber'] === 'string') return `Policy ${d['policyNumber']}`;
    return null;
  }
}
