import pytest


@pytest.mark.xfail(reason="cleanup update_test_amis.py to use moto.ec2.utils.gen_moto_amis")
def test_for_update_test_amis_cleanup():
    from moto.ec2.utils import gen_moto_amis
    return type(gen_moto_amis)
