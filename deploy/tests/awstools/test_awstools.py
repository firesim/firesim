from __future__ import print_function
from pprint import pprint

# Do NOT import any firesim code being tested that might open connections to AWS here.
# Import the firesim code to be tested inside the test functions so that the moto
# mocks are registered before any possible boto connections are created
import boto3
from botocore.stub import Stubber
from moto import mock_sns

from mock import patch
import pytest
from pytest import raises

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
        assert response['ResponseMetadata']['HTTPStatusCode'] == 200
        assert response['Attributes']['TopicArn'] == arn

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
            assert topic_arn == None

            # TODO we could mock rootLogger.critical to capture it's calls and args and validate that we're seeing the correct "nice" message

            # make sure get_snsname_arn() actually called out to get a sns
            # client, otherwise we aren't testing what we think we are
            mock_session.assert_called_once_with('sns')

        aws_res_mock.assert_called_once()
