"""Opt-in concurrency checks using a disposable schema, never the public schema."""
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import text
from sqlmodel import SQLModel,Session,create_engine,select
from app.models import Tenant,CallSession,CallStatus,CallMode,PhoneSuppression,ConversationState
from app.services.conversation_policy import suppress_phone

URL=os.environ.get('PRODUCT_TEST_DATABASE_URL')
pytestmark=pytest.mark.skipif(not URL,reason='isolated PostgreSQL URL not supplied')


@pytest.fixture
def pg():
    schema='product_test_'+uuid4().hex
    admin=create_engine(URL)
    with admin.begin() as conn:conn.execute(text(f'CREATE SCHEMA {schema}'))
    engine=create_engine(URL,connect_args={'options':f'-csearch_path={schema}'})
    try:
        SQLModel.metadata.create_all(engine)
        with Session(engine) as session:
            session.add(Tenant(id=1,code='product-isolated',name='product-isolated'));session.commit()
        yield engine
    finally:
        engine.dispose()
        with admin.begin() as conn:conn.execute(text(f'DROP SCHEMA {schema} CASCADE'))
        admin.dispose()


def test_simultaneous_opt_out_preserves_both_calls_and_one_suppression(pg):
    with Session(pg) as session:
        calls=[CallSession(tenant_id=1,phone='13900000000',mode=CallMode.AI_ONLY,status=CallStatus.IN_AI,attempts=1) for _ in range(8)]
        for call in calls:session.add(call)
        session.commit();ids=[call.id for call in calls]
    def stop(cid):
        with Session(pg) as session:
            session.execute(text("SET LOCAL lock_timeout='5s'"))
            call=session.exec(select(CallSession).where(CallSession.id==cid).with_for_update()).one()
            suppress_phone(session,call,'stop_contact');session.commit()
    with ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(stop,ids))
    with Session(pg) as session:
        assert len(session.exec(select(PhoneSuppression)).all())==1
        assert len(session.exec(select(CallSession).where(CallSession.status==CallStatus.IN_AI)).all())==8


def test_migration_is_repeatable_and_funnel_works_on_postgres(pg):
    from app.api.routers.product import funnel
    from types import SimpleNamespace
    sql=(Path(__file__).parents[1]/'migrations/postgresql/20260908_product_delivery.sql').read_text()
    with pg.connect() as conn:
        # The SQL file owns BEGIN/COMMIT; autocommit avoids nesting a runner transaction.
        conn=conn.execution_options(isolation_level='AUTOCOMMIT')
        for _ in range(2):conn.exec_driver_sql(sql)
    with Session(pg) as session:
        call=CallSession(tenant_id=1,phone='13900000000',mode=CallMode.AI_ONLY,status=CallStatus.COMPLETED,attempts=1)
        session.add(call);session.commit();session.refresh(call)
        session.add(ConversationState(tenant_id=1,call_id=call.id,attempt=1,data_json='{"outcome":"qualified_lead","answer_kind":"human"}'));session.commit()
        result=funnel(days=7,user=SimpleNamespace(tenant_id=1),session=session)
        assert result['groups']==[{'outcome':'qualified_lead','answer_kind':'human','attempts':1,'unique_phones':1}]
