/**
 * How one supporter reads on the Supporters page. Shared by the page's build
 * step and its "Check for new supporters" script, so a name fetched later
 * looks exactly like one baked in.
 *
 * The app shows names only; amounts and messages are for this page.
 */

/** "12 Oct 2026". */
export const dateOf = (iso) =>
	new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });

/** "৳300", "$4.99": the currency's symbol, and decimals only when there are any. */
export function amountOf(amount, currency) {
	if (typeof amount !== 'number' || !currency) return '';
	try {
		return new Intl.NumberFormat('en', {
			style: 'currency',
			currency,
			currencyDisplay: 'narrowSymbol',
			minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
			maximumFractionDigits: 2,
		}).format(amount);
	} catch {
		return `${currency} ${amount}`;
	}
}

/** A coffee's price in each currency SupportKori takes; tips are whole coffees. */
const COFFEE_BDT = 300;
const COFFEE_USD = 5;
/** Roughly what SupportKori converts at, in taka per dollar. */
const BDT_PER_USD = 122;
/** How far a converted amount may drift from whole dollar coffees (rate moves, fees). */
const TOLERANCE = 0.1;

/**
 * A tip or membership as the supporter paid it. Someone paying in dollars
 * reaches us as SupportKori's conversion to taka, ৳618 rather than $5, which
 * no number of ৳300 coffees adds up to. A dollar membership arrives the same
 * way, ৳618 or ৳620 a month for $5. Such an amount is shown as the dollar coffees it is
 * closest to, when it is close enough; anything else is left as it came.
 */
export function paidAs(amount, currency) {
	if (currency !== 'BDT' || typeof amount !== 'number' || amount % COFFEE_BDT === 0) {
		return { amount, currency };
	}
	const usd = amount / BDT_PER_USD;
	const coffees = Math.round(usd / COFFEE_USD);
	if (coffees < 1) return { amount, currency };
	const target = coffees * COFFEE_USD;
	return Math.abs(usd - target) <= target * TOLERANCE ? { amount: target, currency: 'USD' } : { amount, currency };
}

/** "Tip · ৳300 · 12 Oct 2026", leaving out whatever is not known. */
export function metaOf(s) {
	const parts = [s.kind === 'member' ? 'Member' : 'Tip'];
	const paid = paidAs(s.amount, s.currency);
	const amount = amountOf(paid.amount, paid.currency);
	if (amount) parts.push(s.kind === 'member' ? `${amount} a month` : amount);
	parts.push(dateOf(s.at));
	return parts.join(' · ');
}
