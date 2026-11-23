/*
 * Free bet calculator. Runs entirely in the browser on the odds embedded in the page: no requests.
 *
 * Free bet (stake not returned) F on outcome i at odds o_i: if it wins you get W = F * (o_i - 1).
 * Each other outcome j is covered with cash s_j = W / o_j, so every outcome pays the same.
 * Profit = W * (1 - sum_{j != i} 1/o_j).
 * Covers use the best allowed odds: every outcome at a different bookmaker, and never two bookmakers
 * of the same group (e.g. Entain: bwin / sportingbet / vistabet), the free bet bookmaker included.
 */
(function () {
    'use strict';

    const data = window.FREEBET_DATA;
    if (!data || !data.odds || data.odds.length === 0) {
        return;
    }
    const outcomes = data.outcomes;
    const byKey = new Map(data.odds.map(o => [o.bk, o]));

    const amountInput = document.getElementById('fb-amount');
    const bookmakerSelect = document.getElementById('fb-bookmaker');
    const outcomeSelect = document.getElementById('fb-outcome');
    const planBox = document.getElementById('fb-plan');
    const suggestionsBody = document.getElementById('fb-suggestions');
    const suggestionsTitle = document.getElementById('fb-suggestions-title');

    const round2 = v => Math.round(v * 100) / 100;
    const money = v => v.toFixed(2) + ' €';

    /** Same bookmaker, or two bookmakers of the same group. */
    function conflict(a, b) {
        const groupOf = bk => byKey.get(bk).group ?? ('bk:' + bk);
        return groupOf(a) === groupOf(b);
    }

    /** Best cover for the other outcomes: lowest sum(1/odds), no group conflicts. */
    function bestCover(freeBk, others) {
        let best = null;
        let bestSum = Infinity;
        function search(index, chosen, partial) {
            if (partial >= bestSum) return;
            if (index === others.length) {
                bestSum = partial;
                best = chosen.slice();
                return;
            }
            for (const o of data.odds) {
                const price = o.prices[others[index]];
                if (!price) continue;
                if (conflict(o.bk, freeBk) || chosen.some(c => conflict(o.bk, c.bk))) continue;
                chosen.push({bk: o.bk, outcome: others[index], odds: price});
                search(index + 1, chosen, partial + 1 / price);
                chosen.pop();
            }
        }
        search(0, [], 0);
        return best;
    }

    function plan(freeBk, freeOutcome, amount) {
        const source = byKey.get(freeBk);
        const freeOdds = source && source.prices[freeOutcome];
        if (!freeOdds || !(amount > 0)) return null;
        const winnings = amount * (freeOdds - 1);
        const others = outcomes.filter(o => o !== freeOutcome);
        const cover = bestCover(freeBk, others);
        if (!cover) return null;

        cover.forEach(c => c.stake = round2(winnings / c.odds));
        const coverTotal = round2(cover.reduce((s, c) => s + c.stake, 0));
        const profitByOutcome = {};
        profitByOutcome[freeOutcome] = round2(winnings - coverTotal);
        cover.forEach(c => profitByOutcome[c.outcome] = round2(c.stake * c.odds - coverTotal));
        const profit = Math.min(...Object.values(profitByOutcome));
        return {
            free: {bk: freeBk, outcome: freeOutcome, odds: freeOdds, stake: amount},
            cover, coverTotal, profitByOutcome, profit, retention: profit / amount * 100
        };
    }

    // ---- rendering (textContent only, bookmaker names come from the API) ----

    function el(tag, text, className) {
        const e = document.createElement(tag);
        if (text != null) e.textContent = text;
        if (className) e.className = className;
        return e;
    }

    function bookmakerCell(bk) {
        const o = byKey.get(bk);
        const td = el('td');
        td.appendChild(el('span', o.title));
        td.appendChild(document.createTextNode(' '));
        td.appendChild(el('span', '(' + o.age + ')', 'age' + (o.stale ? ' stale' : '')));
        return td;
    }

    function renderPlan(p) {
        planBox.replaceChildren();
        if (!bookmakerSelect.value) {
            planBox.hidden = true;
            return;
        }
        planBox.hidden = false;
        if (!p) {
            planBox.appendChild(el('div', 'Δεν βγαίνει κάλυψη: χρειάζεται για κάθε άλλο αποτέλεσμα διαφορετική εταιρεία, από άλλο όμιλο.', 'errors'));
            return;
        }
        const free = byKey.get(p.free.bk);
        planBox.appendChild(el('h2', 'Free bet ' + money(p.free.stake) + ' στο ' + p.free.outcome + ' @ ' + free.title));

        const table = el('table');
        const head = el('tr');
        ['Αποτέλεσμα', 'Εταιρεία', 'Απόδοση', 'Ποντάρισμα', 'Τύπος', 'Κέρδος αν βγει'].forEach((h, i) =>
            head.appendChild(el('th', h, i >= 2 && i !== 4 ? 'num' : null)));
        table.appendChild(el('thead')).appendChild(head);
        const body = table.appendChild(el('tbody'));

        const rows = [{...p.free, type: 'free bet'}].concat(p.cover.map(c => ({...c, type: 'μετρητά'})));
        rows.forEach(r => {
            const tr = el('tr');
            tr.appendChild(el('td')).appendChild(el('b', r.outcome));
            tr.appendChild(bookmakerCell(r.bk));
            tr.appendChild(el('td', r.odds.toFixed(2), 'num'));
            tr.appendChild(el('td', null, 'num')).appendChild(el('b', money(r.stake)));
            tr.appendChild(el('td', r.type));
            const profit = p.profitByOutcome[r.outcome];
            tr.appendChild(el('td', money(profit), 'num ' + (profit > 0 ? 'profit-pos' : 'profit-neg')));
            body.appendChild(tr);
        });
        planBox.appendChild(table);

        const total = el('div', null, 'plan-total');
        total.appendChild(document.createTextNode('Βάζεις '));
        total.appendChild(el('b', money(p.coverTotal)));
        total.appendChild(document.createTextNode(' μετρητά · σίγουρο κέρδος '));
        total.appendChild(el('b', money(p.profit), p.profit > 0 ? 'profit-pos' : 'profit-neg'));
        total.appendChild(document.createTextNode(' (' + p.retention.toFixed(1) + '% του free bet)'));
        planBox.appendChild(total);
        planBox.appendChild(el('div',
            'Free bet = δεν επιστρέφεται το ποντάρισμα, μόνο τα κέρδη. Τα πονταρίσματα είναι στρογγυλεμένα σε λεπτά. ' +
            'Έλεγξε τις αποδόσεις στις εταιρείες πριν ποντάρεις.', 'muted'));
    }

    function renderSuggestions(amount) {
        const onlyBk = bookmakerSelect.value;
        const plans = [];
        data.odds.forEach(o => {
            if (onlyBk && o.bk !== onlyBk) return;
            Object.keys(o.prices).forEach(outcome => {
                const p = plan(o.bk, outcome, amount);
                if (p) plans.push(p);
            });
        });
        plans.sort((a, b) => b.profit - a.profit);

        suggestionsTitle.textContent = 'Πού να βάλεις το free bet των ' + money(amount) +
            (onlyBk ? ' στην ' + byKey.get(onlyBk).title : '');
        suggestionsBody.replaceChildren();
        if (plans.length === 0) {
            const tr = el('tr');
            const td = el('td', 'Καμία πρόταση.', 'muted');
            td.colSpan = 5;
            tr.appendChild(td);
            suggestionsBody.appendChild(tr);
            return;
        }
        plans.slice(0, 8).forEach(p => {
            const tr = el('tr');
            const freeTd = el('td', null, 'bet');
            const link = el('a', p.free.outcome + ' ' + p.free.odds.toFixed(2) + ' @ ' + byKey.get(p.free.bk).title);
            link.href = '#';
            link.addEventListener('click', e => {
                e.preventDefault();
                select(p.free.bk, p.free.outcome);
            });
            freeTd.appendChild(link);
            tr.appendChild(freeTd);

            const coverTd = el('td');
            p.cover.forEach(c => coverTd.appendChild(el('div',
                c.outcome + ' ' + c.odds.toFixed(2) + ' @ ' + byKey.get(c.bk).title + ' → ' + money(c.stake), 'bet')));
            tr.appendChild(coverTd);
            tr.appendChild(el('td', money(p.coverTotal), 'num'));
            tr.appendChild(el('td', money(p.profit), 'num ' + (p.profit > 0 ? 'profit-pos' : 'profit-neg')));
            tr.appendChild(el('td', p.retention.toFixed(1) + '%', 'num'));
            suggestionsBody.appendChild(tr);
        });
    }

    function markSelected() {
        document.querySelectorAll('.odd-link').forEach(a => a.classList.toggle('selected',
            a.dataset.bk === bookmakerSelect.value && a.dataset.outcome === outcomeSelect.value));
    }

    function update() {
        const amount = parseFloat(amountInput.value);
        const valid = amount > 0;
        renderPlan(valid && bookmakerSelect.value ? plan(bookmakerSelect.value, outcomeSelect.value, amount) : null);
        if (valid) renderSuggestions(amount);
        markSelected();
    }

    function select(bk, outcome) {
        bookmakerSelect.value = bk;
        outcomeSelect.value = outcome;
        update();
        planBox.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    }

    document.querySelectorAll('.odd-link').forEach(a => a.addEventListener('click', e => {
        e.preventDefault();
        select(a.dataset.bk, a.dataset.outcome);
    }));
    window.freebetPlan = plan; // for testing from the console
    amountInput.addEventListener('input', update);
    bookmakerSelect.addEventListener('change', update);
    outcomeSelect.addEventListener('change', update);
    update();
})();
