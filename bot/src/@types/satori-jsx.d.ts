import "react";

// Satori aceita o atributo `tw` (classes Tailwind) em qualquer elemento HTML
declare module "react" {
  interface HTMLAttributes<T> {
    tw?: string;
  }
}
