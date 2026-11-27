from decimal import Decimal as D


def account(client, name):
    response = client.post("/accounts", json={"username": name})
    assert response.status_code == 200
    return {"X-API-Key": response.json()["api_key"]}


def usd(client, headers):
    rows = client.get("/portfolio", headers=headers).json()
    return D(next(item["available"] for item in rows if item["symbol"] == "USD"))


def create_company(client, headers, symbol="NOVA"):
    response = client.post("/assets", headers=headers, json={
        "symbol": symbol, "name": "Nova Systems", "description": "Technology company",
        "total_supply": "1000000",
    })
    assert response.status_code == 201


def company(client, headers, symbol="NOVA"):
    response = client.get(f"/companies/{symbol}/development", headers=headers)
    assert response.status_code == 200
    return response.json()


def test_company_has_five_real_development_branches(client):
    headers = account(client, "owner_one")
    create_company(client, headers)
    state = company(client, headers)
    assert set(state["next_costs"]) == {"office", "research", "marketing", "production", "logistics"}
    assert state["employees"] == 3
    assert D(state["hourly_revenue"]) > D(state["hourly_expenses"])


def test_company_capital_staff_and_dividends(client):
    headers = account(client, "owner_two")
    create_company(client, headers)
    funded = client.post("/companies/NOVA/capital", headers=headers, json={"amount": "1000"})
    assert funded.status_code == 200
    assert D(funded.json()["cash"]) == D("1000")

    hired = client.post("/companies/NOVA/staff", headers=headers, json={"action": "hire", "count": 2})
    assert hired.status_code == 200 and hired.json()["employees"] == 5
    fired = client.post("/companies/NOVA/staff", headers=headers, json={"action": "fire", "count": 1})
    assert fired.status_code == 200 and fired.json()["employees"] == 4

    withdrawn = client.post("/companies/NOVA/dividend", headers=headers, json={"amount": "100"})
    assert withdrawn.status_code == 200
    assert D(withdrawn.json()["cash"]) < D(fired.json()["cash"])


def test_company_upgrades_change_operating_economics(client):
    headers = account(client, "owner_three")
    create_company(client, headers)

    # Ulepszenia są opłacane z kasy firmy, więc najpierw wnosimy kapitał.
    funded = client.post(
        "/companies/NOVA/capital",
        headers=headers,
        json={"amount": "1000"},
    )
    assert funded.status_code == 200, funded.text
    assert D(funded.json()["cash"]) >= D("1000")

    before = company(client, headers)
    upgraded = client.post("/companies/NOVA/upgrade", headers=headers, json={"branch": "production"})
    assert upgraded.status_code == 200
    after = upgraded.json()
    assert after["production_level"] == 1
    assert D(after["hourly_revenue"]) > D(before["hourly_revenue"])



def test_dividend_is_paid_pro_rata_to_current_shareholders(client):
    owner = account(client, "owner_dividend")
    investor = account(client, "investor_dividend")
    create_company(client, owner, "DIVI")
    sell = client.post("/orders", headers=owner, json={
        "symbol": "DIVI", "side": "sell", "order_type": "limit",
        "quantity": "250000", "price": "0.01",
    })
    assert sell.status_code == 200
    buy = client.post("/orders", headers=investor, json={
        "symbol": "DIVI", "side": "buy", "order_type": "limit",
        "quantity": "250000", "price": "0.01",
    })
    assert buy.status_code == 200
    assert buy.json()["status"] == "FILLED"
    assert client.post("/companies/DIVI/capital", headers=owner, json={"amount": "1000"}).status_code == 200
    owner_before, investor_before = usd(client, owner), usd(client, investor)
    result = client.post("/companies/DIVI/dividend", headers=owner, json={"amount": "100"})
    assert result.status_code == 200
    assert D(result.json()["dividends_total"]) == D("100")
    assert usd(client, owner) - owner_before == D("75")
    assert usd(client, investor) - investor_before == D("25")

def test_company_actions_require_owner_and_validate_amounts(client):
    owner = account(client, "owner_four")
    stranger = account(client, "stranger_four")
    create_company(client, owner)
    assert client.post("/companies/NOVA/capital", headers=stranger, json={"amount": "100"}).status_code == 403
    assert client.post("/companies/NOVA/capital", headers=owner, json={"amount": "-1"}).status_code == 400
    assert client.post("/companies/NOVA/capital", headers=owner, json={"amount": "1000000001"}).status_code == 400
    assert client.post("/companies/NOVA/staff", headers=owner, json={"action": "hire", "count": 99}).status_code == 400
