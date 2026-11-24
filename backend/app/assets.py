
from fastapi import HTTPException
from .db import transaction
from .engine import set_balance

def create_user_asset(account_id,data):
    with transaction() as db:
        if db.execute("SELECT 1 FROM assets WHERE symbol=?",(data.symbol,)).fetchone():
            raise HTTPException(409,"Ticker already exists")

        db.execute("""
            INSERT INTO assets(symbol,name,last_price,description,creator_id,total_supply)
            VALUES(?,?,?,?,?,?)
        """,(data.symbol,data.name,"0",data.description,account_id,str(data.total_supply)))

        set_balance(db,account_id,data.symbol,data.total_supply,0)

        return {
            "symbol":data.symbol,
            "name":data.name,
            "description":data.description,
            "total_supply":str(data.total_supply),
            "creator_id":account_id,
            "last_price":"0"
        }
