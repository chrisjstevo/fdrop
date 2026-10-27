# Sample LME Clear Tradeable Instruments File (TIF v2.1)

Test data only. `TIF_20261027_2033_001.xml` holds 100 made-up instruments
following *LME Clear Tradeable Instrument File v2.1 Specification*. Regenerate with:

    python3 generate_tif.py            # --seed N for a different set, --out-dir DIR

Business date 2026-10-27, EOD run (20:33, spec section 4).

## Contents

| Kind | Rows | TYPE | CFI | CONTRACT_TYPE |
|---|---|---|---|---|
| Forwards (cash, daily, weekly, 3rd-Wed monthly; USD/EUR/GBP/JPY) | 33 | F | FCEPSX | FUTR |
| Options (call/put pairs, European) | 28 | T | OCEFPS / OPEFPS | OPTN |
| TAPOs | 12 | A | OCXTCS / OPXTCS | TAPO |
| Monthly average futures | 8 | F | FCECSX | MAFT |
| LMEminis | 5 | F | FCECSX | FUTR |
| Cash-settled futures, ferrous (steel) | 7 | F | FCECSX | FERR |
| Cash-settled futures, other (cobalt, lithium, alumina, moly, premiums) | 7 | F | FCECSX | FUTR |

Consistency rules applied: every option's UNDERLYING_ISIN is the ISIN of a forward
in the same file (same contract code, prompt = 3rd Wednesday of the option month);
option expiry = 1st Wednesday, TAPO/MAF/CSF maturity = last business day, minis =
3rd Wednesday; prompts avoid weekends and UK bank holidays; ISIN check digits are
valid ISO 6166; deltas are Black-76 from indicative prices, in [0,1] for calls
and [-1,0] for puts, and blank on legs that have not traded (spec section 4).

## Where the spec is ambiguous, and what was chosen

- **File name**: section 3 is titled "naming convention" but gives none. Name built
  from the header fields: `TIF_<REPORT_DATE>_<REPORT_TIME>_<REPORT_VERSION>.xml`.
- **Format**: only the XML layout (3.2.1) is described, so the file is XML, UTF-8, LF
  line endings. The example's stray leading spaces in values and `</OPTION_DELTA >`
  are treated as typos; empty fields are written as `<X></X>` like the example.
- **REPORT_NAME**: 3.2 says "Tradable Instrument File EOD", 3.2.1 says "Tradeable
  Instrument File"; used the 3.2 value. REPORT_VERSION `001` (3.2), not `100` (3.2.1).
- **CONTRACT_TYPE**: Char(4), "see the Appendix", but no appendix lists them. Used
  codes the spec itself shows: FUTR (forwards, per the 3.2.1 example), TAPO, MAFT
  (appendix A), FERR (added in v1.5) for steel CSFs, and OPTN for options (the Daily
  Positions Report type the spec maps TYPE T to). Appendix A's "FORWARD" is 7 chars and
  breaks Char(4), so not used. Minis and non-ferrous CSFs use FUTR — a guess.
- **SPOT_MONTH**: OTHER, not OTHR (v2.1 change log). "New logic" of appendix A: on
  2026-10-27 forwards with prompt ≤ 2026-11-18 are SPOT; for monthly instruments the
  earliest unexpired month is SPOT (Nov-26 options, Oct-26 TAPO/MAF/CSF, Nov-26 minis).
- **Option CFI**: OCEFPS/OPEFPS, since the business date is after 2026-09-21.
- **STRIKE_PRICE**: written without trailing zeros (`3250`), as in the example.
