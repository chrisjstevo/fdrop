#!/usr/bin/env python3
"""Generate a sample LME Clear Tradeable Instruments File (TIF v2.1).

Test data only: instruments, ISINs and deltas are made up, but follow the
TIF v2.1 specification (section 3.1 fields, 3.2 XML header, appendices A-C).

    python3 generate_tif.py [--out-dir DIR] [--seed N]

Writes TIF_<REPORT_DATE>_<REPORT_TIME>_<REPORT_VERSION>.xml (see README.md
for why that name: the spec gives no explicit file name).
"""

import argparse
import math
import random
import string
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from xml.sax.saxutils import escape

# --- Report header (spec 3.2) ------------------------------------------------
BUSINESS_DATE = date(2026, 10, 27)          # EOD run for this business date
REPORT_TIME = "2033"                        # EOD production time (spec 4)
REPORT_VERSION = "001"
REPORT_NAME = "Tradable Instrument File EOD"
ISSUE_CODE = "N"
ROW_TARGET = 100

COLUMNS = [
    "UPDATE_DATE_TIME", "CONTRACT_NAME", "CONTRACT_CODE", "TYPE", "CFI",
    "MATURITY", "STRIKE_PRICE", "ISIN", "UNDERLYING_ISIN", "SPOT_MONTH",
    "CONTRACT_TYPE", "OPTION_DELTA",
]

# --- Calendar ----------------------------------------------------------------
# England & Wales bank holidays (LME non-prompt days) for the dates we generate.
HOLIDAYS = {
    date(2026, 12, 25), date(2026, 12, 28),
    date(2027, 1, 1), date(2027, 3, 26), date(2027, 3, 29), date(2027, 5, 3),
    date(2027, 5, 31), date(2027, 8, 30), date(2027, 12, 27), date(2027, 12, 28),
    date(2028, 1, 3), date(2028, 4, 14), date(2028, 4, 17), date(2028, 5, 1),
    date(2028, 5, 29), date(2028, 8, 28), date(2028, 12, 25), date(2028, 12, 26),
}


def is_bd(d):
    return d.weekday() < 5 and d not in HOLIDAYS


def add_bd(d, n):
    while n:
        d += timedelta(days=1)
        if is_bd(d):
            n -= 1
    return d


def nth_weekday(y, m, weekday, n):
    d = date(y, m, 1)
    d += timedelta(days=(weekday - d.weekday()) % 7)
    return d + timedelta(weeks=n - 1)


def third_wed(y, m):
    return nth_weekday(y, m, 2, 3)


def first_wed(y, m):
    return nth_weekday(y, m, 2, 1)


def last_bd(y, m):
    d = date(y + m // 12, m % 12 + 1, 1) - timedelta(days=1)
    while not is_bd(d):
        d -= timedelta(days=1)
    return d


def months_from(y, m, n):
    """The n months starting at (y, m)."""
    out = []
    for _ in range(n):
        out.append((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


# --- Reference data (spec appendix B) ----------------------------------------
# code -> (contract name stem, currency, indicative price for strikes/deltas)
METALS = {
    "AHD": ("Primary Aluminium", "USD", 2650), "AHE": ("Primary Aluminium", "EUR", 2280),
    "AHS": ("Primary Aluminium", "GBP", 1990), "AHY": ("Primary Aluminium", "JPY", 398000),
    "CAD": ("Copper", "USD", 10250), "CAE": ("Copper", "EUR", 8820),
    "CAS": ("Copper", "GBP", 7700),
    "ZSD": ("Zinc", "USD", 2960), "PBD": ("Lead", "USD", 1995),
    "PBE": ("Lead", "EUR", 1715),
    "NID": ("Primary Nickel", "USD", 15800), "NIS": ("Primary Nickel", "GBP", 11870),
    "SND": ("Tin", "USD", 34200), "SNY": ("Tin", "JPY", 5140000),
    "AAD": ("Aluminium Alloy", "USD", 2480), "NAD": ("NASAAC", "USD", 2560),
    "COD": ("Cobalt", "USD", 33000),
}
MAF = {  # monthly average futures: code -> (name, underlying forward code)
    "OAD": ("Primary Aluminium Monthly Average Future USD", "AHD"),
    "OCD": ("Copper Monthly Average Future USD", "CAD"),
    "OZD": ("Zinc Monthly Average Future USD", "ZSD"),
    "OND": ("Primary Nickel Monthly Average Future USD", "NID"),
    "OPD": ("Lead Monthly Average Future USD", "PBD"),
    "OSD": ("Tin Monthly Average Future USD", "SND"),
}
MINIS = {
    "MAD": "Primary Aluminium LMEmini USD", "MCD": "Copper LMEmini USD",
    "MZD": "Zinc LMEmini USD",
}
# cash-settled futures: code -> (name, contract type). Ferrous CSFs use FERR
# (contract type code added in spec v1.5); see README for the others.
CSF = {
    "HCD": ("LME Steel HRC FOB China (Argus) USD", "FERR"),
    "HND": ("LME Steel HRC NW Europe (Argus) USD", "FERR"),
    "HUD": ("LME Steel HRC N. America (Platts) USD", "FERR"),
    "SCD": ("LME Steel Scrap CFR Turkey (Platts) USD", "FERR"),
    "SRD": ("LME Steel Rebar FOB Turkey (Platts) USD", "FERR"),
    "STD": ("LME Steel Scrap CFR Taiwan (Argus) USD", "FERR"),
    "CBD": ("LME Cobalt (Fastmarkets MB) USD", "FUTR"),
    "LHD": ("LME Lithium Hydroxide CIF (Fastmarkets MB) USD", "FUTR"),
    "AMD": ("LME Alumina (Platts) USD", "FUTR"),
    "MDD": ("LME Molybdenum (Platts) USD", "FUTR"),
    "UPD": ("LME Aluminium Premium Duty Paid US Midwest (Platts) USD", "FUTR"),
    "EDD": ("LME Aluminium Premium Duty Paid European (Fastmarkets MB) USD", "FUTR"),
}
STRIKE_STEP = {"AHD": 25, "CAD": 50, "ZSD": 25, "PBD": 25, "NID": 250, "SND": 500}
VOL = {"AHD": 0.19, "CAD": 0.22, "ZSD": 0.24, "PBD": 0.21, "NID": 0.30, "SND": 0.27}

# --- Helpers -----------------------------------------------------------------
_used_isins = set()


def isin_check_digit(body):
    digits = "".join(str(int(c, 36)) for c in body)
    total = 0
    for i, ch in enumerate(reversed(digits)):
        n = int(ch)
        if i % 2 == 0:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return str((10 - total % 10) % 10)


def new_isin(rng):
    alphabet = string.ascii_uppercase + string.digits
    while True:
        body = "GB00" + "".join(rng.choice(alphabet) for _ in range(7))
        isin = body + isin_check_digit(body)
        if isin not in _used_isins:
            _used_isins.add(isin)
            return isin


def norm_cdf(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def black76_delta(fwd, strike, vol, expiry, is_call, rate=0.04):
    t = max((expiry - BUSINESS_DATE).days, 1) / 365.0
    d1 = (math.log(fwd / strike) + 0.5 * vol * vol * t) / (vol * math.sqrt(t))
    disc = math.exp(-rate * t)
    return disc * norm_cdf(d1) if is_call else -disc * norm_cdf(-d1)


def fmt_delta(x):
    s = f"{x:.6f}"
    return "0.000000" if s == "-0.000000" else s


def fmt_strike(x):
    s = f"{x:.13f}".rstrip("0").rstrip(".")
    return s


def ts(rng, start, end):
    """Random UTC timestamp in [start, end], formatted per UPDATE_DATE_TIME."""
    span = (end - start).total_seconds()
    t = start + timedelta(seconds=rng.uniform(0, span))
    t = t.replace(microsecond=rng.randrange(1000) * 1000)
    return t.strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def utc(d, hh=0, mm=0):
    return datetime.combine(d, time(hh, mm), tzinfo=timezone.utc)


# --- SPOT/OTHER (spec appendix A, "new logic") -------------------------------
def forward_spot_limit(bd):
    """Last prompt date that is SPOT for daily-expiring forwards on business date bd."""
    tw = third_wed(bd.year, bd.month)
    roll = tw - timedelta(days=1)
    while not is_bd(roll):
        roll -= timedelta(days=1)
    if bd < roll:
        return tw
    y, m = months_from(bd.year, bd.month, 2)[1]
    return third_wed(y, m)


FWD_SPOT_LIMIT = forward_spot_limit(BUSINESS_DATE)


def spot_forward(prompt):
    return "SPOT" if prompt <= FWD_SPOT_LIMIT else "OTHER"


def spot_monthly(maturity, front):
    return "SPOT" if maturity == front else "OTHER"


# --- Record builders ---------------------------------------------------------
def row(**kw):
    r = {c: "" for c in COLUMNS}
    r.update(kw)
    return r


def forward(rng, code, prompt):
    name, ccy, _ = METALS[code]
    upd = ts(rng, utc(BUSINESS_DATE - timedelta(days=120)), utc(BUSINESS_DATE, 0, 40))
    if spot_forward(prompt) == "SPOT" and prompt > third_wed(2026, 10):
        upd = ts(rng, utc(date(2026, 10, 21), 0, 40), utc(date(2026, 10, 21), 0, 42))
    return row(
        UPDATE_DATE_TIME=upd,
        CONTRACT_NAME=f"{name} Future {ccy} {prompt:%Y%m%d}",
        CONTRACT_CODE=code, TYPE="F", CFI="FCEPSX",
        MATURITY=prompt.isoformat(), ISIN=new_isin(rng),
        SPOT_MONTH=spot_forward(prompt), CONTRACT_TYPE="FUTR",
    )


def option(rng, code, ym, strike, is_call, underlying_isin, traded):
    name, ccy, price = METALS[code]
    expiry = first_wed(*ym)
    cp = "C" if is_call else "P"
    delta = ""
    if traded:
        delta = fmt_delta(black76_delta(price, strike, VOL[code], expiry, is_call))
        upd = ts(rng, utc(BUSINESS_DATE, 20, 5), utc(BUSINESS_DATE, 20, 30))
    else:
        upd = ts(rng, utc(BUSINESS_DATE - timedelta(days=60), 9), utc(BUSINESS_DATE, 19))
    return row(
        UPDATE_DATE_TIME=upd,
        CONTRACT_NAME=f"{name} Option {ccy} {expiry:%Y%m%d} {fmt_strike(strike)}{cp}",
        CONTRACT_CODE=code, TYPE="T",
        CFI="OCEFPS" if is_call else "OPEFPS",   # European style after 2026-09-21
        MATURITY=expiry.isoformat(), STRIKE_PRICE=fmt_strike(strike),
        ISIN=new_isin(rng), UNDERLYING_ISIN=underlying_isin,
        SPOT_MONTH=spot_monthly(expiry, OPTION_FRONT), CONTRACT_TYPE="OPTN",
        OPTION_DELTA=delta,
    )


def tapo(rng, code, ym, strike, is_call, traded):
    name, ccy, price = METALS[code]
    expiry = last_bd(*ym)
    cp = "C" if is_call else "P"
    delta = ""
    if traded:
        # averaging lowers effective vol; good enough for test data
        delta = fmt_delta(black76_delta(price, strike, VOL[code] * 0.6, expiry, is_call))
        upd = ts(rng, utc(BUSINESS_DATE, 20, 5), utc(BUSINESS_DATE, 20, 30))
    else:
        upd = ts(rng, utc(BUSINESS_DATE - timedelta(days=90), 9), utc(BUSINESS_DATE, 19))
    return row(
        UPDATE_DATE_TIME=upd,
        CONTRACT_NAME=f"{name} TAPO {ccy} {expiry:%Y%m%d} {fmt_strike(strike)}{cp}",
        CONTRACT_CODE=code, TYPE="A", CFI="OCXTCS" if is_call else "OPXTCS",
        MATURITY=expiry.isoformat(), STRIKE_PRICE=fmt_strike(strike),
        ISIN=new_isin(rng), SPOT_MONTH=spot_monthly(expiry, MONTHLY_FRONT),
        CONTRACT_TYPE="TAPO", OPTION_DELTA=delta,
    )


def monthly_future(rng, code, name, maturity, front, contract_type):
    upd = ts(rng, utc(BUSINESS_DATE - timedelta(days=200)), utc(BUSINESS_DATE, 0, 40))
    if maturity == front:
        # rolled to SPOT when the previous front month expired
        rolled = min(front.replace(day=1) - timedelta(days=1), BUSINESS_DATE - timedelta(days=6))
        upd = ts(rng, utc(rolled, 0, 40), utc(BUSINESS_DATE, 0, 40))
    return row(
        UPDATE_DATE_TIME=upd, CONTRACT_NAME=f"{name} {maturity:%Y%m%d}",
        CONTRACT_CODE=code, TYPE="F", CFI="FCECSX",
        MATURITY=maturity.isoformat(), ISIN=new_isin(rng),
        SPOT_MONTH=spot_monthly(maturity, front), CONTRACT_TYPE=contract_type,
    )


# Front (SPOT) months on BUSINESS_DATE for monthly-expiring instruments.
OPTION_FRONT = next(first_wed(y, m) for y, m in months_from(2026, 10, 3)
                    if first_wed(y, m) >= BUSINESS_DATE)
MONTHLY_FRONT = next(last_bd(y, m) for y, m in months_from(2026, 10, 3)
                     if last_bd(y, m) >= BUSINESS_DATE)
MINI_FRONT = next(third_wed(y, m) for y, m in months_from(2026, 10, 3)
                  if third_wed(y, m) >= BUSINESS_DATE)


def build(rng):
    rows = []
    cash = add_bd(BUSINESS_DATE, 2)
    y, m = months_from(cash.year, cash.month, 4)[3]
    three_m = cash.replace(year=y, month=m)
    while not is_bd(three_m):
        three_m += timedelta(days=1)

    # Forwards that underlie the options below: 3rd Wednesday of the option month.
    opt_months = months_from(2026, 11, 3) + [(2027, 3), (2027, 6)]
    underlying = {}
    opt_metals = ["AHD", "CAD", "ZSD", "NID", "PBD", "SND"]
    for i, code in enumerate(opt_metals):
        for ym in (opt_months[i % 3], opt_months[3 + i % 2]):
            prompt = third_wed(*ym)
            if (code, prompt) not in underlying:
                r = forward(rng, code, prompt)
                underlying[(code, prompt)] = r["ISIN"]
                rows.append(r)

    # Further forwards across the date structure: cash, daily, weekly, monthly.
    prompts = [cash, three_m, add_bd(BUSINESS_DATE, 7), add_bd(BUSINESS_DATE, 23),
               date(2027, 2, 10), date(2027, 4, 21), date(2027, 9, 15), date(2028, 6, 21)]
    for code, prompt in zip(["CAD", "AHD", "ZSD", "NID", "AHD", "CAD", "AAD", "NAD"], prompts):
        rows.append(forward(rng, code, prompt))
    for code, prompt in [("AHE", three_m), ("AHS", date(2026, 12, 16)),
                         ("AHY", date(2027, 1, 20)), ("CAE", cash),
                         ("CAS", date(2027, 3, 17)), ("PBE", date(2026, 11, 25)),
                         ("NIS", date(2026, 12, 16)), ("SNY", date(2027, 1, 20)),
                         ("COD", date(2026, 12, 16)), ("SND", three_m),
                         ("PBD", add_bd(BUSINESS_DATE, 12)), ("ZSD", date(2027, 6, 16)),
                         ("AHD", date(2029, 12, 19))]:
        rows.append(forward(rng, code, prompt))

    # Options: call/put pairs around the money. Only traded legs carry a delta.
    for i, code in enumerate(opt_metals):
        price, step = METALS[code][2], STRIKE_STEP[code]
        months = [opt_months[i % 3], opt_months[3 + i % 2]]
        if code in ("AHD", "CAD"):
            months.append(months[0])        # a second strike in the front series
        for ym in months:
            atm = round(price / step) * step
            strike = atm + rng.choice([-4, -2, 0, 2, 4, 6]) * step
            und = underlying[(code, third_wed(*ym))]
            traded_leg = rng.choice([True, False])          # True: call traded
            both = rng.random() < 0.5
            rows.append(option(rng, code, ym, strike, True, und, traded_leg or both))
            rows.append(option(rng, code, ym, strike, False, und, (not traded_leg) or both))

    # TAPOs (monthly average options, expire last business day of the month).
    for code, ym in [("AHD", (2026, 10)), ("AHD", (2026, 12)), ("CAD", (2026, 11)),
                     ("CAD", (2027, 3)), ("ZSD", (2027, 1)), ("PBD", (2026, 12))]:
        price, step = METALS[code][2], STRIKE_STEP[code]
        strike = round(price / step) * step + rng.choice([-2, 0, 2, 4]) * step
        traded_leg = rng.choice([True, False])
        rows.append(tapo(rng, code, ym, strike, True, traded_leg))
        rows.append(tapo(rng, code, ym, strike, False, not traded_leg))

    # Monthly average futures (MAFT), maturity = last business day of month.
    for code, ym in [("OAD", (2026, 10)), ("OAD", (2027, 1)), ("OCD", (2026, 11)),
                     ("OCD", (2027, 6)), ("OZD", (2026, 10)), ("OND", (2026, 12)),
                     ("OPD", (2027, 2)), ("OSD", (2026, 11))]:
        rows.append(monthly_future(rng, code, MAF[code][0], last_bd(*ym), MONTHLY_FRONT, "MAFT"))

    # LMEminis: monthly, 3rd Wednesday prompt.
    for code, ym in [("MAD", (2026, 11)), ("MAD", (2027, 2)), ("MCD", (2026, 11)),
                     ("MCD", (2026, 12)), ("MZD", (2027, 1))]:
        rows.append(monthly_future(rng, code, MINIS[code], third_wed(*ym), MINI_FRONT, "FUTR"))

    # Cash-settled futures, monthly, settling on the last business day.
    for code, ym in [("HCD", (2026, 10)), ("HND", (2026, 11)), ("HUD", (2027, 1)),
                     ("SCD", (2026, 10)), ("SCD", (2027, 3)), ("SRD", (2026, 12)),
                     ("STD", (2026, 11)), ("CBD", (2026, 12)), ("LHD", (2026, 10)),
                     ("LHD", (2027, 6)), ("AMD", (2026, 11)), ("MDD", (2027, 1)),
                     ("UPD", (2026, 10)), ("EDD", (2026, 12))]:
        name, ctype = CSF[code]
        rows.append(monthly_future(rng, code, name, last_bd(*ym), MONTHLY_FRONT, ctype))

    if len(rows) != ROW_TARGET:
        raise SystemExit(f"built {len(rows)} rows, expected {ROW_TARGET}")
    rows.sort(key=lambda r: (r["CONTRACT_CODE"], r["TYPE"], r["MATURITY"], r["CONTRACT_NAME"]))
    return rows


def render(rows):
    out = ['<?xml version="1.0" ?>', "<REPORT>",
           f'<IDENTIFICATION REPORT_CODE="TIF" REPORT_NAME="{REPORT_NAME}" '
           f'REPORT_DATE="{BUSINESS_DATE:%Y%m%d}" REPORT_TIME="{REPORT_TIME}" '
           f'REPORT_VERSION="{REPORT_VERSION}"/>',
           f"<ISSUE_CODE>{ISSUE_CODE}</ISSUE_CODE>",
           f'<CNTS COLUMN_COUNT="{len(COLUMNS)}" ROW_COUNT="{len(rows)}"/>',
           "<DATA>"]
    for r in rows:
        out.append("<ROW>")
        out += [f"<{c}>{escape(r[c])}</{c}>" for c in COLUMNS]
        out.append("</ROW>")
    out += ["</DATA>", "</REPORT>"]
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out-dir", default=Path(__file__).resolve().parent, type=Path)
    ap.add_argument("--seed", default=20261027, type=int)
    args = ap.parse_args()
    rows = build(random.Random(args.seed))
    path = args.out_dir / f"TIF_{BUSINESS_DATE:%Y%m%d}_{REPORT_TIME}_{REPORT_VERSION}.xml"
    path.write_text(render(rows), encoding="utf-8", newline="\n")
    print(f"wrote {path} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
