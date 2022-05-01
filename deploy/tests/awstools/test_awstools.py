from __future__ import annotations

from pprint import pprint

# Do NOT import any firesim code being tested that might open connections to AWS here.
# Import the firesim code to be tested inside the test functions so that the moto
# mocks are registered before any possible boto connections are created
import boto3
from botocore.stub import Stubber
from moto import mock_sns, mock_ec2

from unittest.mock import patch
import pytest
import sure
import re

# In case you put any package-level tests, make sure they use the test credentials too
pytestmark = pytest.mark.usefixtures("aws_test_credentials")

@pytest.mark.usefixtures("aws_test_credentials")
class TestSNS(object):
    """Test functions in awstools that work with SNS"""

    # When we're testing awstools.get_snsname_arn(), we don't also want to be
    # testing awstools.aws_resource_names(). mainly because we're subjugating
    # the boto3.client factory (in the second test below) and also because we want to be able to run these
    # tests while not on a AWS EC2 host and aws_resource_names() assumes that it
    # can lookup the AWS instance-id via 169.254.169.254 API
    @patch('awstools.awstools.aws_resource_names', return_value={'snsname': 'Testing'})
    # register the moto backend for sns
    @mock_sns
    def test_get_snsname_arn_sanity(self, aws_res_mock):
        """Simple call returns properly formed ARN"""
        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import get_snsname_arn

        arn = get_snsname_arn()

        client = boto3.client('sns')
        response = client.get_topic_attributes(TopicArn=arn)

        # output will normally be captured and suppressed but printed
        # iff the test fails. So, leaving in something that dumps the response
        # can be useful. See https://docs.pytest.org/en/4.6.x/capture.html
        pprint(response)
        response['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
        response['Attributes']['TopicArn'].should.equal(arn)

        # check that our mock of aws_resource_names was used
        aws_res_mock.assert_called_once()


    @patch('awstools.awstools.aws_resource_names', return_value={'snsname': 'Testing'})
    @mock_sns
    def test_get_snsname_arn_auth_exception_handling(self, aws_res_mock):
        """AuthorizationError is intercepted and re-raised as AssertionError"""
        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import get_snsname_arn

        # create a mock SNS client that returns what we tell it to
        client = boto3.client('sns')
        stub = Stubber(client)
        stub.add_client_error('create_topic', service_error_code='AuthorizationError')
        stub.activate()


        # since firesim manager code doesn't take clients as method parameters
        # now we mock boto3.client to return our stubbed client
        with patch.object(boto3._get_default_session(), 'client', return_value=client) as mock_session:
            topic_arn = get_snsname_arn()

            stub.assert_no_pending_responses()
            topic_arn.should.be.none

            # TODO we could mock rootLogger.critical to capture it's calls and args and validate that we're seeing the correct "nice" message

            # make sure get_snsname_arn() actually called out to get a sns
            # client, otherwise we aren't testing what we think we are
            mock_session.assert_called_once_with('sns')

        aws_res_mock.assert_called_once()

@mock_ec2
@pytest.mark.dependency()
def test_get_f1_ami_id():
    """get_f1_ami_id() shouldn't throw or return an invalid ami id"""

    # local imports of code-under-test ensure moto has mocks
    # registered before any possible calls out to AWS
    from awstools.awstools import get_f1_ami_id
    try:
        ami = get_f1_ami_id()
    except Exception as e:
        pytest.fail(f"get_f1_ami_id() raised {e} and this likely means you need to run 'scripts/update_test_amis.py'")

    if re.match(r"^ami-[0-9a-f]+$",ami) is None:
        pytest.fail(f"'{ami}' doesn't look like a legit AMI ID and this likely means you need to run 'scripts/update_test_amis.py'")

from packaging import version
from moto import __version__ as moto_version
@pytest.mark.skipif(version.parse(moto_version) < version.parse("3.0.4dev"), reason="These tests require https://github.com/spulec/moto/pull/4853")
@pytest.mark.dependency(depends=["test_get_f1_ami_id"])
@pytest.mark.usefixtures("aws_test_credentials")
class TestLaunchInstances(object):
    """Test functions in awstools that Launch EC2 Instances"""

    @pytest.mark.xfail(strict=True, reason="moto doesn't validate instance types.")
    @mock_ec2
    def test_invalid_instance_type_fails(self):
        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import launch_instances, run_block_device_dict

        # launch_instances requires vpc setup as done by firesim/scripts/setup_firesim.py
        from awstools.aws_setup import aws_setup
        aws_setup()

        with pytest.raises(Exception):
            instances = launch_instances('INVALID_TYPE', 1,
                                         instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                         blockdevices=run_block_device_dict(),
                                         tags={'fsimcluster': 'testcluster', 'secondtag': 'secondvalue'})

    @mock_ec2
    def test_can_create_multiple_instance_tags(self):
        """Can pass multiple tags to launch_instances"""

        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import launch_instances, run_block_device_dict

        # launch_instances requires vpc setup as done by firesim/scripts/setup_firesim.py
        from awstools.aws_setup import aws_setup
        aws_setup()

        instances = launch_instances('f1.2xlarge', 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     tags={'fsimcluster': 'testcluster', 'secondtag': 'secondvalue'})
        instances.shouldnt.be.empty

        ids = [i.id for i in instances]
        ids.shouldnt.be.empty

        ec2_client = boto3.client('ec2')

        paginator = ec2_client.get_paginator('describe_instances')

        operation_params = {
            'InstanceIds': ids
        }
        page_iterator = paginator.paginate(**operation_params)

        all_reservations = []
        for page in page_iterator:
            page['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
            all_reservations += page['Reservations']

        tags = {t['Key']:t['Value'] for t in all_reservations[0]['Instances'][0]['Tags']}
        tags.should.have.key('fsimcluster')
        tags['fsimcluster'].should.equal('testcluster')
        tags.should.have.key('secondtag')
        tags['secondtag'].should.equal('secondvalue')

    @mock_ec2
    def test_moto_resets_instances_between_tests(self):
        ec2_client = boto3.client('ec2')
        paginator = ec2_client.get_paginator('describe_instances')
        page_iterator = paginator.paginate()

        all_reservations = []
        for page in page_iterator:
            page['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
            all_reservations += page['Reservations']

        all_reservations.should.be.empty

    @mock_ec2
    def test_can_query_multiple_instance_tags(self):
        """get_instances_by_tag_type returns only instances matching all tags"""

        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import launch_instances, run_block_device_dict, get_instances_by_tag_type

        # launch_instances requires vpc setup as done by firesim/scripts/setup_firesim.py
        from awstools.aws_setup import aws_setup
        aws_setup()

        tag1 = {'fsimcluster': 'testcluster'}
        type = 'f1.2xlarge'

        # create an instance with only a single tag
        instances = launch_instances(type, 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     tags=tag1)
        instances.should.have.length_of(1)

        tag2 = { 'secondtag': 'secondvalue' }
        # create an instance with additional tag
        instances = launch_instances(type, 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     tags={**tag1, **tag2})
        instances.shouldnt.be.empty

        # There should be two instances total now, across two reservations
        ec2_client = boto3.client('ec2')

        paginator = ec2_client.get_paginator('describe_instances')
        page_iterator = paginator.paginate()

        all_reservations = []
        for page in page_iterator:
            page['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
            all_reservations += page['Reservations']
        all_reservations.should.have.length_of(2)

        [i for r in all_reservations for i in r['Instances']].should.have.length_of(2)

        # get_instances_by_tag_type with both tags should only return one instance
        instances = get_instances_by_tag_type({**tag1, **tag2},type)
        list(instances).should.have.length_of(1)

        # and that instance should be the one with both tags
        ids = [i.id for i in instances]
        ids.shouldnt.be.empty

        operation_params = {
            'InstanceIds': ids
        }

        paginator = ec2_client.get_paginator('describe_instances')
        page_iterator = paginator.paginate(**operation_params)

        all_reservations = []
        for page in page_iterator:
            page['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
            all_reservations += page['Reservations']

        tags = {t['Key']:t['Value'] for t in all_reservations[0]['Instances'][0]['Tags']}
        tags.should.equal({**tag1, **tag2})

        # get_instances_by_tag_type with only the original tag should return both instances
        instances = get_instances_by_tag_type(tag1,type)
        list(instances).should.have.length_of(2)

    @mock_ec2
    def test_additive_instance_creation(self):
        """create_instances always_expand=True always adds `count`"""

        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import launch_instances, run_block_device_dict

        # launch_instances requires vpc setup as done by firesim/scripts/setup_firesim.py
        from awstools.aws_setup import aws_setup
        aws_setup()

        type = 'f1.2xlarge'

        instances = launch_instances(type, 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     always_expand=True)
        instances.should.have.length_of(1)

        instances = launch_instances(type, 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     always_expand=True)
        instances.should.have.length_of(1)

        # There should be two instances total now, across two reservations
        ec2_client = boto3.client('ec2')

        paginator = ec2_client.get_paginator('describe_instances')
        page_iterator = paginator.paginate()

        all_reservations = []
        for page in page_iterator:
            page['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
            all_reservations += page['Reservations']
        all_reservations.should.have.length_of(2)

        [i for r in all_reservations for i in r['Instances']].should.have.length_of(2)

    @mock_ec2
    def test_non_additive_requires_tags(self):
        """create_instances always_expand=False throws if tags aren't given"""

        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import launch_instances, run_block_device_dict

        # launch_instances requires vpc setup as done by firesim/scripts/setup_firesim.py
        from awstools.aws_setup import aws_setup
        aws_setup()

        type = 'f1.2xlarge'

        with pytest.raises(ValueError):
            launch_instances(type, 1,
                             instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                             blockdevices=run_block_device_dict(),
                             always_expand=False)

    @mock_ec2
    def test_non_additive_instance_creation(self):
        """create_instances always_expand=False checks for existing instances when tags!=None"""

        # local imports of code-under-test ensure moto has mocks
        # registered before any possible calls out to AWS
        from awstools.awstools import launch_instances, run_block_device_dict

        # launch_instances requires vpc setup as done by firesim/scripts/setup_firesim.py
        from awstools.aws_setup import aws_setup
        aws_setup()

        type = 'f1.2xlarge'

        instances = launch_instances(type, 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     tags = {'fsimcluster': 'testcluster'},
                                     always_expand=False)
        instances.should.have.length_of(1)

        instances = launch_instances(type, 1,
                                     instancemarket="ondemand", spotinterruptionbehavior=None, spotmaxprice=None,
                                     blockdevices=run_block_device_dict(),
                                     tags = {'fsimcluster': 'testcluster'},
                                     always_expand=False)
        instances.should.have.length_of(1)

        # There should be one instance total now, across one reservation
        ec2_client = boto3.client('ec2')
        paginator = ec2_client.get_paginator('describe_instances')
        page_iterator = paginator.paginate()

        all_reservations = []
        for page in page_iterator:
            page['ResponseMetadata']['HTTPStatusCode'].should.equal(200)
            all_reservations += page['Reservations']
        all_reservations.should.have.length_of(1)

        [i for r in all_reservations for i in r['Instances']].should.have.length_of(1)
