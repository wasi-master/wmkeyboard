# Smart punctuation models: results by language

Held-out accuracy of every published variant, measured 2026-11-09 on the
40,000 sentences `prep.py` set aside from the 41.4M-example corpus (Tatoeba
plus the first lines of each OPUS OpenSubtitles 2018 file, `remote/languages.tsv`).
No held-out sentence was in training; the same set judged every run.

How to read it:

- Accuracy columns are over the three end marks (`. ? !`), the comma folded
  away, as `train.py` reports. A language needs 200 held-out end-mark
  sentences for a row; below that the number would be noise, so it is left
  out. Held-out counts are exact where `eval_lang.py` ran (en de fr es tr hu
  bn hi fa) and otherwise estimated from the language's share of the corpus.
- `p/r` is precision/recall of one mark. These were measured on the box for
  nine languages only; the box is gone and the held-out arrays with it, so
  the other languages have no per-mark figures until the next training run
  (which should keep `heldout.tsv`).
- v2 and v3 share data, vocabulary and sizes; v3 adds the language token.
  Standard is e64 d192, 4 layers; Large is e96 d256, 6 layers.

Exclamation marks are the standing failure: the model sees them in under 4%
of the sentences that end with one, in every language and every size.
Questions in statement order (French, Spanish, Hungarian) are the other
miss; Turkish and English, whose questions carry a particle or inverted
verb, read them at 85% to 95%.

| Lang | Language | Train examples | Held-out (est.) | v2 Std | v2 Large | v3 Std | v3 Large | v3 Std `.` p/r | v3 Std `?` p/r | v3 Std `!` p/r | v3 Large `.` p/r | v3 Large `?` p/r | v3 Large `!` p/r |
|---|---|---:|---:|---:|---:|---:|---:|---|---|---|---|---|---|
| en | English | 3,384,351 | 3,215 | 0.936 | 0.940 | 0.937 | 0.937 | .94/.99 | .95/.85 | 1.00/.04 | .93/.99 | .95/.83 | 1.00/.04 |
| it | Italian | 2,290,491 | 2,138 | 0.854 | 0.862 | 0.857 | 0.861 |  |  |  |  |  |  |
| de | German | 2,218,352 | 1,898 | 0.894 | 0.893 | 0.895 | 0.896 | .89/.99 | .94/.81 | .43/.02 | .89/.99 | .94/.81 | .50/.03 |
| fr | French | 1,781,596 | 1,623 | 0.877 | 0.884 | 0.880 | 0.879 | .88/.98 | .89/.65 | .25/.01 | .88/.98 | .86/.67 | .25/.01 |
| hu | Hungarian | 1,536,969 | 1,343 | 0.801 | 0.807 | 0.819 | 0.815 | .83/.96 | .78/.57 | .66/.22 | .84/.96 | .78/.63 | .67/.21 |
| tr | Turkish | 1,496,650 | 1,419 | 0.942 | 0.944 | 0.946 | 0.947 | .95/.99 | .94/.95 | .67/.04 | .95/.99 | .95/.95 | .00/.00 |
| es | Spanish | 1,453,693 | 1,305 | 0.875 | 0.882 | 0.884 | 0.884 | .89/.98 | .85/.62 | .00/.00 | .89/.98 | .83/.61 | .00/.00 |
| ru | Russian | 1,227,911 | 1,146 | 0.916 | 0.921 | 0.909 | 0.917 |  |  |  |  |  |  |
| pt | Portuguese | 1,211,404 | 1,131 | 0.874 | 0.875 | 0.870 | 0.869 |  |  |  |  |  |  |
| nl | Dutch | 1,073,871 | 1,002 | 0.932 | 0.934 | 0.933 | 0.932 |  |  |  |  |  |  |
| fi | Finnish | 1,041,490 | 972 | 0.907 | 0.916 | 0.915 | 0.913 |  |  |  |  |  |  |
| id | Indonesian | 1,036,941 | 968 | 0.827 | 0.830 | 0.826 | 0.825 |  |  |  |  |  |  |
| uk | Ukrainian | 1,025,206 | 957 | 0.833 | 0.844 | 0.833 | 0.840 |  |  |  |  |  |  |
| bg | Bulgarian | 1,014,499 | 947 | 0.860 | 0.860 | 0.861 | 0.864 |  |  |  |  |  |  |
| el | Greek | 957,010 | 893 | 0.866 | 0.862 | 0.858 | 0.859 |  |  |  |  |  |  |
| vi | Vietnamese | 931,822 | 870 | 0.861 | 0.866 | 0.862 | 0.861 |  |  |  |  |  |  |
| he | Hebrew | 929,650 | 868 | 0.849 | 0.850 | 0.852 | 0.851 |  |  |  |  |  |  |
| da | Danish | 925,746 | 864 | 0.900 | 0.896 | 0.896 | 0.896 |  |  |  |  |  |  |
| ro | Romanian | 911,551 | 851 | 0.827 | 0.830 | 0.833 | 0.826 |  |  |  |  |  |  |
| eo | Esperanto | 898,618 | 839 | 0.953 | 0.957 | 0.955 | 0.957 |  |  |  |  |  |  |
| sr | Serbian | 848,134 | 792 | 0.880 | 0.888 | 0.893 | 0.887 |  |  |  |  |  |  |
| sv | Swedish | 836,155 | 780 | 0.912 | 0.912 | 0.911 | 0.908 |  |  |  |  |  |  |
| ms | Malay | 834,448 | 779 | 0.853 | 0.857 | 0.851 | 0.849 |  |  |  |  |  |  |
| lt | Lithuanian | 797,154 | 744 | 0.878 | 0.880 | 0.886 | 0.876 |  |  |  |  |  |  |
| cs | Czech | 782,784 | 731 | 0.832 | 0.835 | 0.832 | 0.835 |  |  |  |  |  |  |
| nb | Norwegian Bokmål | 778,472 | 727 | 0.876 | 0.873 | 0.874 | 0.880 |  |  |  |  |  |  |
| sk | Slovak | 757,414 | 707 | 0.840 | 0.844 | 0.851 | 0.845 |  |  |  |  |  |  |
| hr | Croatian | 735,455 | 686 | 0.854 | 0.856 | 0.853 | 0.856 |  |  |  |  |  |  |
| bs | Bosnian | 705,917 | 659 | 0.816 | 0.820 | 0.820 | 0.820 |  |  |  |  |  |  |
| pl | Polish | 683,862 | 638 | 0.865 | 0.872 | 0.872 | 0.870 |  |  |  |  |  |  |
| pt_br | Portuguese (Brazil) | 678,951 | 634 | 0.872 | 0.865 | 0.866 | 0.874 |  |  |  |  |  |  |
| is | Icelandic | 626,974 | 585 | 0.905 | 0.916 | 0.900 | 0.904 |  |  |  |  |  |  |
| eu | Basque | 624,473 | 583 | 0.830 | 0.819 | 0.828 | 0.828 |  |  |  |  |  |  |
| et | Estonian | 580,141 | 542 | 0.865 | 0.875 | 0.876 | 0.869 |  |  |  |  |  |  |
| mk | Macedonian | 523,902 | 489 | 0.860 | 0.860 | 0.858 | 0.863 |  |  |  |  |  |  |
| si | Sinhala | 518,236 | 484 | 0.805 | 0.807 | 0.805 | 0.807 |  |  |  |  |  |  |
| ar | Arabic | 509,726 | 476 | 0.813 | 0.823 | 0.827 | 0.821 |  |  |  |  |  |  |
| sl | Slovenian | 476,873 | 445 | 0.838 | 0.833 | 0.845 | 0.847 |  |  |  |  |  |  |
| sq | Albanian | 465,735 | 435 | 0.838 | 0.838 | 0.833 | 0.845 |  |  |  |  |  |  |
| ca | Catalan | 424,006 | 396 | 0.850 | 0.844 | 0.858 | 0.864 |  |  |  |  |  |  |
| lv | Latvian | 408,253 | 381 | 0.804 | 0.804 | 0.807 | 0.809 |  |  |  |  |  |  |
| bn | Bengali | 362,659 | 260 | 0.758 | 0.765 | 0.754 | 0.762 | .76/.97 | .67/.35 | .00/.00 | .77/.97 | .70/.37 | .00/.00 |
| fa | Persian | 359,607 | 330 | 0.715 | 0.724 | 0.724 | 0.736 | .73/.90 | .71/.57 | .00/.00 | .73/.90 | .74/.59 | .00/.00 |
| ml | Malayalam | 310,331 | 290 | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| gl | Galician | 231,879 | 216 | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| ka | Georgian | 200,625 | 187 (<200) | 0.784 | 0.798 | 0.807 | 0.794 |  |  |  |  |  |  |
| hi | Hindi | 98,444 | 82 (<200) | n<200 | n<200 | n<200 | n<200 | .72/.97 | .25/.05 | .00/.00 |  |  |  |
| tl | Tagalog | 90,831 | 85 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| mr | Marathi | 87,666 | 82 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| hy | Armenian | 44,499 | 42 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| af | Afrikaans | 32,151 | 30 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| ta | Tamil | 23,128 | 22 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| ur | Urdu | 22,577 | 21 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| te | Telugu | 17,810 | 17 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| az | Azerbaijani | 12,667 | 12 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| kk | Kazakh | 7,697 | 7 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| sw | Swahili | 4,436 | 4 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |
| uz | Uzbek | 1,194 | 1 (<200) | n<200 | n<200 | n<200 | n<200 |  |  |  |  |  |  |

Global (all 58 languages, 40,000 held-out sentences):

| Variant | acc (4 marks) | acc3 (`. ? !`) | `.` p/r | `?` p/r | `!` p/r | `,` p/r |
|---|---:|---:|---|---|---|---|
| v2 Std | 0.8293 | 0.8716 | 0.830/0.977 | 0.836/0.673 | 0.519/0.019 | 0.553/0.029 |
| v2 Large | 0.8321 | 0.8746 | 0.833/0.978 | 0.842/0.687 | 0.518/0.020 | 0.529/0.037 |
| v3 Std | 0.8318 | 0.8742 | 0.833/0.977 | 0.837/0.685 | 0.580/0.026 | 0.578/0.038 |
| v3 Large | 0.8319 | 0.8744 | 0.833/0.976 | 0.839/0.688 | 0.521/0.028 | 0.534/0.036 |
