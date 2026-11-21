import { Directive, ElementRef, Input, OnChanges, OnDestroy, OnInit, Renderer2 } from '@angular/core';

interface LogoReview {
  light: string;
  dark: string;
  review: string;
}

// Central review catalog for the product logos used in resource cards. The
// dark variant is selected only where the original art loses contrast.
const LOGO_REVIEWS: Record<string, LogoReview> = {
  redis: { light: 'redis.svg', dark: 'redis.svg', review: 'native colors remain distinct on both surfaces' },
  apachekafka: { light: 'apachekafka.svg', dark: 'apachekafka-dark.svg', review: 'monochrome dark mark needs a light counterpart in dark mode' },
  grafana: { light: 'grafana.svg', dark: 'grafana.svg', review: 'warm multicolor mark remains distinct on both surfaces' },
  jaeger: { light: 'jaeger.svg', dark: 'jaeger.svg', review: 'multicolor mark remains distinct on both surfaces' },
  docker: { light: 'docker.svg', dark: 'docker.svg', review: 'blue mark remains distinct on both surfaces' },
  python: { light: 'python.svg', dark: 'python.svg', review: 'blue and yellow mark remains distinct on both surfaces' },
  java: { light: 'java.svg', dark: 'java.svg', review: 'red and blue mark remains distinct on both surfaces' },
  nodejs: { light: 'nodejs.svg', dark: 'nodejs.svg', review: 'green mark remains distinct on both surfaces' },
  npm: { light: 'npm.svg', dark: 'npm.svg', review: 'red mark remains distinct on both surfaces' },
  amazonwebservices: { light: 'amazonwebservices.svg', dark: 'amazonwebservices.svg', review: 'orange mark remains distinct on both surfaces' },
  git: { light: 'git.svg', dark: 'git.svg', review: 'orange mark remains distinct on both surfaces' },
  maven: { light: 'maven.svg', dark: 'maven.svg', review: 'multicolor mark remains distinct on both surfaces' },
  gradle: { light: 'gradle.svg', dark: 'gradle.svg', review: 'green mark remains distinct on both surfaces' },
  pnpm: { light: 'pnpm.svg', dark: 'pnpm.svg', review: 'multicolor mark remains distinct on both surfaces' },
  yarn: { light: 'yarn.svg', dark: 'yarn.svg', review: 'blue mark remains distinct on both surfaces' },
  go: { light: 'go.svg', dark: 'go.svg', review: 'blue mark remains distinct on both surfaces' },
};

@Directive({ selector: 'img[rbReviewedLogo]', standalone: true })
export class ReviewedLogoDirective implements OnChanges, OnInit, OnDestroy {
  @Input('rbReviewedLogo') logo = '';
  private observer?: MutationObserver;

  constructor(private element: ElementRef<HTMLImageElement>, private renderer: Renderer2) {}

  ngOnChanges() { this.apply(); }
  ngOnInit() {
    this.observer = new MutationObserver(() => this.apply());
    this.observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-appearance'] });
  }

  ngOnDestroy() { this.observer?.disconnect(); }

  private apply() {
    const review = LOGO_REVIEWS[this.logo] || LOGO_REVIEWS.docker;
    const dark = document.documentElement.dataset['appearance'] === 'dark';
    this.renderer.setAttribute(this.element.nativeElement, 'src', 'assets/tool-logos/' + (dark ? review.dark : review.light));
    this.renderer.setAttribute(this.element.nativeElement, 'data-logo-review', review.review);
  }
}
