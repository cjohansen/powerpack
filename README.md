# Stasis Powerpack

A batteries-included Clojure toolkit for building static web sites. It combines
the excellent [Stasis](https://github.com/magnars/stasis) with some useful
tools, and provides the wiring so you can focus on coding your pages - exactly
the way you want.

## Install

With tools.deps:

```clj
no.cjohansen/powerpack {:mvn/version "2023.12.20"}
```

With Leiningen:

```clj
[no.cjohansen/powerpack "2023.12.20"]
```

## Status

Powerpack is based on several sites built on roughly the same architecture over
a 5 year period. The specific Powerpack code-base has been developed alongside a
5000 page static page and has been thoroughly put through its paces. APIs are
stable, development experience is top notch, and the production export is truly
production ready. Use at will!

## What does it do?

Stasis Powerpack reads your raw content from Markdown (including
[mapdown](https://github.com/magnars/mapdown)) and EDN files (by default, you
can include more file types as needed) into an in-memory Datomic database, and
configures the web server with an asset optimization pipeline, image
manipulation, live reload during development, and optionally more stuff - like
code syntax highlighting.

Powerpack doesn't have any opinions on how you construct pages, there are no
themes or design. It's still a toolkit, but the overall dataflow is lined up for
you, and the web server is tuned well for both development and production. It
comes with an `export` function that exports all your pages, as well as images
and assets, ready for deployment to your serverless static web host of choice.

## What's in the box?

Stasis Powerpack combines the following libraries:

- [Stasis](https://github.com/magnars/stasis) - static web site toolkit
- [Optimus](https://github.com/magnars/optimus) - asset optimization pipeline
- [Datomic](https://datomic.com) - relational database
- [Imagine](https://github.com/cjohansen/imagine) - image manipulation (crop,
  resize, greyscale, etc)
- [m1p](https://github.com/cjohansen/m1p) - i18n
- [Mapdown](https://github.com/magnars/mapdown) - key/value markdown files
- [Beholder](https://github.com/nextjournal/beholder) - file watching (live
  reload)
- [Clygments](https://github.com/bfontaine/clygments) - Clojure wrapper for
  Pygments (syntax highlighting) (This might be peeled out as an optional add-on
  library)

## Powerpack data flow overview

In Powerpack, you put content in Markdown files and EDN files. These are read
and transacted into an in-memory Datomic database. Powerpack defines a few
schema attributes it needs to address pages, but other than that you'll need to
define your own schema and implement some mapping to prepare raw content for the
database. The files can contain whatever you want -- supporting data, a single
web page, multiple web pages -- it's up to you.

Powerpack will allow browsing to any entity that has a `:page/uri`. You must add
these attributes to any content you want to be browsable.

When you request a URL from the development server, Powerpack will find the page
in the database and call on your code to render a response. This response can be
any valid ring response. Powerpack provides some convenience library functions
to help you build HTML pages, but you're free to ignore them. Before serving the
page to the user, Powerpack will perform some post processing on the pages.

Powerpack currently only post processes HTML pages. When it does, images will be
optimized, or passed through [Imagine](https://github.com/cjohansen/imagine),
open graph URLs will be qualified with your production domain name, code blocks
will be highlighted, etc. Powerpack has an extension point for this post
processing.

## Getting started

To build a site with Powerpack, you must provide some configuration and a
function that renders pages. Eventually you will probably also want to create a
[Datomic schema](https://docs.datomic.com/pro/schema/schema.html). There is
a [comprehensive step-by-step getting started in a separate
repo](https://github.com/cjohansen/powerblog).

## Rationale

I've built a few Stasis pages in my time, and I keep wiring together the same
things. Over time I've accumulated a toolkit of really useful things that I
always want when I do static web pages. So I thought it would be worth trying to
tie them all up in a neat package.

Normally I'm very adverse to "don't call us, we'll call you" style frameworks,
and especially ones that are just a grab-bag of dependencies. So why introduce
another such beast into the world?

I think that if the web server is configured well enough, you typically won't
need to "own" it when you make static web sites. Your focus will be on crafting
content and coding pages. Because the deployment target is a bunch of static
files, you won't keep adding middlewares and tweaking the setup. In other words,
I suspect it might be possible to "set and forget" the server, thus leaving
control of it to a framework like Powerpack might be acceptable.

Based on the experience of building a 5000 page static site with Powerpack in a
two person team, I would consider this hypothesis validated.

Powerpack combines a bunch of tools in a package. However, it makes no effort to
hide them or "unify" them in any way. They are reasonably configured and wired
them together for you, but configuration is passed through, and no features are
hidden or proxyed in any way. This should reduce the risk of Powerpack becoming
a bottle neck or unnecessary layer of confusion. Obviously, it is a middleman,
but the individual tools are used as transparently as possible.

## Changelog

### 2023.12.20

Initial release

## License

Copyright © 2023 Christian Johansen and Magnar Sveen

Distributed under the Eclipse Public License either version 1.0 or (at your
option) any later version.
