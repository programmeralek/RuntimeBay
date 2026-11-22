import { AfterViewInit, Directive, ElementRef, HostListener, OnDestroy } from '@angular/core';
import { gsap } from 'gsap';

@Directive({ selector: '[rbBrandMark]', standalone: true })
export class BrandMarkDirective implements AfterViewInit, OnDestroy {
  private fill?: HTMLSpanElement;
  private reducedMotion?: MediaQueryList;

  constructor(private host: ElementRef<HTMLElement>) {}

  ngAfterViewInit() {
    const mark = this.host.nativeElement.querySelector<HTMLElement>('.brand-mark');
    if (!mark) return;
    this.fill = document.createElement('span');
    this.fill.className = 'brand-mark-fill';
    this.fill.setAttribute('aria-hidden', 'true');
    mark.append(this.fill);
    this.reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
    gsap.set(this.fill, { clipPath: 'inset(100% 0 0 0)' });
  }

  @HostListener('mouseenter') onEnter() { this.reveal(); }
  @HostListener('mouseleave') onLeave() { this.hide(); }
  @HostListener('focusin') onFocus() { this.reveal(); }
  @HostListener('focusout', ['$event']) onBlur(event: FocusEvent) {
    if (!this.host.nativeElement.contains(event.relatedTarget as Node | null)) this.hide();
  }

  ngOnDestroy() {
    if (this.fill) gsap.killTweensOf(this.fill);
    this.fill?.remove();
  }

  private reveal() {
    if (!this.fill) return;
    gsap.killTweensOf(this.fill);
    if (this.reducedMotion?.matches) gsap.set(this.fill, { clipPath: 'inset(0)' });
    else gsap.to(this.fill, { clipPath: 'inset(0)', duration: 0.72, ease: 'power3.out' });
  }

  private hide() {
    if (!this.fill) return;
    gsap.killTweensOf(this.fill);
    if (this.reducedMotion?.matches) gsap.set(this.fill, { clipPath: 'inset(100% 0 0 0)' });
    else gsap.to(this.fill, { clipPath: 'inset(100% 0 0 0)', duration: 0.42, ease: 'power2.inOut' });
  }
}
