import logging
import random
import string

import pytest
import sure

rootLogger = logging.getLogger()

# In case you put any package-level tests, make sure they use the test credentials too
pytestmark = pytest.mark.usefixtures("aws_test_credentials")

@pytest.mark.usefixtures("aws_test_credentials")
class TestFireSimTags:
    "Test serialization and deserialization of AGFI descriptions"

    def test_str256(self):
        from awstools.afitools import STR256, ILLEGAL_KV_CHARS

        rl = rootLogger

        rl.info('STR256 allows empty string')
        STR256.validate('').should.be.equal('')

        # make a grab-bag of legal string characters
        legal = ''.join(set(c for c in string.ascii_letters + string.digits).difference(ILLEGAL_KV_CHARS))

        rl.info('STR256 limits string length to 255')
        s = ''.join(random.SystemRandom().choice(legal) for _ in range(56))
        STR256.validate(s).should.equal(s)

        s = ''.join(random.SystemRandom().choice(legal) for _ in range(256))
        STR256.validate.when.called_with(s).should.throw(Exception)

        rl.info('STR256 does not allow ILLEGAL_KV_CHARS')
        s = ''.join(random.SystemRandom().choice(legal) for _ in range(56)) + ILLEGAL_KV_CHARS
        STR256.validate.when.called_with(s).should.throw(Exception)

        rl.info('STR256 does not allow integer type')
        with pytest.raises(Exception):
            STR256().validate(123)
        # maybe this should be changed to just coerce things into str, in which case we would
        #STR256().validate(123).should.equal(str(123))


    def test_strbool(self):
        from awstools.afitools import STRBOOL

        rl = rootLogger

        rl.info('STRBOOL takes bools')
        STRBOOL.validate(True).should.equal(True)
        STRBOOL.validate(False).should.equal(False)

        rl.info('STRBOOL coerces known strings to bool')
        for s in ('yes', 'YES', 'TRUE', '1'):
            STRBOOL.validate(s).should.equal(True)

        for s in ('no', 'NO', 'False', '0'):
            STRBOOL.validate(s).should.equal(False)

        rl.info('STRBOOL throws for unknown strings')
        for s in ('bogus', 'not_coerceable'):
            STRBOOL.validate.when.called_with(s).should.throw(Exception)