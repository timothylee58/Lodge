import { Component, input } from '@angular/core';
import { TimelineEntry } from '../core/models';
import { EVENT_LABEL, money, when } from './format';

@Component({
  selector: 'cd-timeline',
  standalone: true,
  template: `
    <ol class="timeline">
      @for (e of entries(); track $index) {
        <li>
          <div class="tl-head"><strong>{{ label(e.type) }}</strong><time>{{ when(e.at) }}</time></div>
          @if (text(e); as t) { <p class="muted">{{ t }}</p> }
        </li>
      } @empty { <li class="muted">No activity yet.</li> }
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
