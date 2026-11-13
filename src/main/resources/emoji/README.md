# Emoji shortcode data

`shortcodes.properties` contains the 853 Unicode-backed shortcodes from
[rxaviers' emoji cheat sheet](https://gist.github.com/rxaviers/7360908)
([raw list](https://gist.githubusercontent.com/rxaviers/7360908/raw)).
Unicode values come from [GitHub's gemoji data](https://github.com/github/gemoji/blob/master/db/emoji.json),
under the MIT license included in `LICENSE`. Values retain emoji presentation
selectors, keycap sequences, flags, and zero-width joiners. The data is bundled;
rendering does not fetch external resources or images.

Six historical names are mapped to their modern gemoji aliases:

| Cheat sheet name | Modern alias |
| --- | --- |
| `person_with_pouting_face` | `pouting_face` |
| `person_frowning` | `frowning_person` |
| `person_with_blond_hair` | `blond_haired_person` |
| `squirrel` | `chipmunk` |
| `black_square` | `black_large_square` |
| `white_square` | `white_large_square` |

The remaining 15 entries in the 868-name cheat sheet are GitHub-specific images,
not Unicode emoji: `bowtie`, `neckbeard`, `feelsgood`, `finnadie`, `goberserk`,
`godmode`, `hurtrealbad`, `rage1`, `rage2`, `rage3`, `rage4`, `suspect`, `trollface`,
`octocat`, and `shipit`. These remain literal shortcodes rather than using
unrelated Unicode substitutes or remote images.

To refresh the snapshot, extract the backtick-delimited names from the raw cheat
sheet, join them to gemoji's `aliases`/`emoji` fields, apply the six aliases above,
and write UTF-8 `name=emoji` lines in cheat-sheet order. Check that all 868 names
are accounted for (853 mappings and 15 image-only entries), retain the license,
and run the renderer tests.
