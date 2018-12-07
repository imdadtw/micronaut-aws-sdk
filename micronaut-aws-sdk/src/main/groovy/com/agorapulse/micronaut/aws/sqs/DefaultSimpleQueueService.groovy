package com.agorapulse.micronaut.aws.sqs

import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.*
import groovy.transform.CompileStatic
import groovy.transform.Synchronized
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
class DefaultSimpleQueueService implements SimpleQueueService {

    public static final String QUEUE_ARN = 'QueueArn'
    private final AmazonSQS client
    private final SimpleQueueServiceConfiguration configuration

    private final Map<String, String> queueUrlByNames = [:]

    DefaultSimpleQueueService(
        AmazonSQS client,
        SimpleQueueServiceConfiguration configuration
    ) {
        this.client = client
        this.configuration = configuration
    }

    @Override
    String getDefaultQueueName() {
        assertDefaultQueueName()
        return configuration.queue
    }

    @Override
    boolean isCaching() {
        return configuration.cache
    }
/**
     *
     * @param queueName
     * @return queue URL
     */
    String createQueue(String queueName) {
        CreateQueueRequest createQueueRequest = new CreateQueueRequest(queueName)
        if (configuration.delaySeconds) {
            createQueueRequest.attributes['DelaySeconds'] = configuration.delaySeconds.toString()
        }
        if (configuration.messageRetentionPeriod) {
            createQueueRequest.attributes['MessageRetentionPeriod'] = configuration.messageRetentionPeriod.toString()
        }
        if (configuration.maximumMessageSize) {
            createQueueRequest.attributes['MaximumMessageSize'] = configuration.maximumMessageSize.toString()
        }
        if (configuration.visibilityTimeout) {
            createQueueRequest.attributes['VisibilityTimeout'] = configuration.visibilityTimeout.toString()
        }
        if (configuration.fifo) {
            createQueueRequest.attributes['FifoQueue'] = 'true'
        }
        String queueUrl = client.createQueue(createQueueRequest).queueUrl
        log.debug "Queue created (queueUrl=$queueUrl)"
        addQueue(queueUrl)
        queueUrl
    }

    /**
     *
     * @param queueUrl
     * @param receiptHandle
     */
    void deleteMessage(String queueName,
                       String receiptHandle) {
        String queueUrl = getQueueUrl(queueName)
        client.deleteMessage(new DeleteMessageRequest(queueUrl, receiptHandle))
        log.debug "Message deleted (queueUrl=$queueUrl)"
    }

    /**
     *
     * @param queueName
     */
    void deleteQueue(String queueName) {
        String queueUrl = getQueueUrl(queueName)
        client.deleteQueue(queueUrl)
        removeQueue(queueUrl)
    }

    /**
     *
     * @param queueName
     * @return
     */
    Map getQueueAttributes(String queueName) {
        String queueUrl = getQueueUrl(queueName)

        GetQueueAttributesRequest getQueueAttributesRequest = new GetQueueAttributesRequest(queueUrl).withAttributeNames(['All'])
        Map attributes = [:]
        try {
            attributes = client.getQueueAttributes(getQueueAttributesRequest).attributes
        } catch(AmazonServiceException exception) {
            if (exception.errorCode == 'AWS.SimpleQueueService.NonExistentQueue') {
                removeQueue(queueUrl)
            }
            log.warn 'An amazon service exception was catched while getting queue attributes', exception
        } catch (AmazonClientException exception) {
            log.warn 'An amazon client exception was catched while getting queue attributes', exception
        }
        attributes
    }

    /**
     *
     * @param jobName
     * @param autoCreate
     * @return
     */
    String getQueueUrl(String queueName) {
        if (queueName.startsWith('http://') || queueName.startsWith('https://')) {
            return queueName
        }
        if (configuration.queueNamePrefix) {
            queueName = configuration.queueNamePrefix + queueName
        }
        if (!queueUrlByNames && caching) {
            loadQueues()
        }

        String queueUrl = caching ? queueUrlByNames[queueName] : getQueueUrlDirect(queueName)
        if (!queueUrl && (configuration.autoCreateQueue)) {
            queueUrl = createQueue(queueName)
        }
        if (!queueUrl) {
            throw new QueueDoesNotExistException("Queue ${queueName} not found")
        }
        queueUrl
    }

    private String getQueueUrlDirect(String queueName) {
        try {
            return client.getQueueUrl(queueName).queueUrl
        } catch (QueueDoesNotExistException ignored) {
            return null
        }
    }

    @Override
    String getQueueArn(String queueName) {
        GetQueueAttributesResult attributes = client.getQueueAttributes(getQueueUrl(queueName), Collections.singletonList(QUEUE_ARN))
        return attributes.getAttributes()[QUEUE_ARN]
    }
/**
     *
     * @param reload
     * @return
     */
    List<String> listQueueNames(boolean reload) {
        if (!queueUrlByNames || reload) {
            loadQueues()
        }
        queueUrlByNames?.keySet()?.sort()
    }

    /**
     *
     * @param reload
     * @return
     */
    List<String> listQueueUrls(boolean reload) {
        if (!queueUrlByNames || reload) {
            loadQueues()
        }
        queueUrlByNames?.values()?.sort()
    }

    /**
     *
     * @param queueName
     * @param maxNumberOfMessages
     * @param visibilityTimeout
     * @param waitTimeSeconds
     * @return
     */
    List<Message> receiveMessages(String queueName, int maxNumberOfMessages, int visibilityTimeout, int waitTimeSeconds) {
        String queueUrl = getQueueUrl(queueName)

        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(queueUrl)
        if (maxNumberOfMessages) {
            receiveMessageRequest.maxNumberOfMessages = maxNumberOfMessages
        }
        if (visibilityTimeout) {
            receiveMessageRequest.visibilityTimeout = visibilityTimeout
        }
        if (waitTimeSeconds) {
            receiveMessageRequest.waitTimeSeconds = waitTimeSeconds
        }
        List<Message> messages = client.receiveMessage(receiveMessageRequest).messages
        log.debug "Messages received (count=${messages.size()})"
        messages
    }

    /**
     * 
     * @param queueName
     * @param messageBody
     * @param delaySeconds
     * @return
     */
    String sendMessage(String queueName, String messageBody, int delaySeconds, String groupId) {
        String queueUrl = getQueueUrl(queueName)

        SendMessageRequest request = new SendMessageRequest(queueUrl, messageBody)
        if (delaySeconds) {
            request.delaySeconds = delaySeconds
        }
        if (groupId) {
            request.messageGroupId = groupId
        }
        String messageId = client.sendMessage(request).messageId
        log.debug "Message sent (messageId=$messageId)"
        messageId
    }

    // PRIVATE

    void assertDefaultQueueName() {
        assert configuration.queue, "Default queue must be defined"
    }

    @Synchronized
    private void addQueue(String queueUrl) {
        assert queueUrl, "Invalid queueUrl"
        queueUrlByNames[getQueueNameFromUrl(queueUrl)] = queueUrl
    }

    @Synchronized
    private void loadQueues() {
        ListQueuesRequest listQueuesRequest = new ListQueuesRequest()
        if (configuration.queueNamePrefix) {
            listQueuesRequest.withQueueNamePrefix configuration.queueNamePrefix
        }
        List queueUrls = client.listQueues(listQueuesRequest).queueUrls
        queueUrlByNames.clear()
        queueUrlByNames.putAll queueUrls?.collectEntries { queueUrl ->
            [getQueueNameFromUrl(queueUrl), queueUrl]
        }

    }

    @Synchronized
    private void removeQueue(String queueUrl) {
        assert queueUrl, "Invalid queueUrl"
        queueUrlByNames.remove(getQueueNameFromUrl(queueUrl))
    }

    private static String getQueueNameFromUrl(String queueUrl) {
        queueUrl.tokenize('/').last()
    }

}
