package org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.get;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.apache.log4j.Logger;
import org.buddycloud.channelserver.channel.ChannelManager;
import org.buddycloud.channelserver.channel.node.configuration.field.AccessModel;
import org.buddycloud.channelserver.db.CloseableIterator;
import org.buddycloud.channelserver.db.exception.NodeStoreException;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.JabberPubsub;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.PubSubElementProcessor;
import org.buddycloud.channelserver.packetprocessor.iq.namespace.pubsub.PubSubGet;
import org.buddycloud.channelserver.pubsub.accessmodel.AccessModels;
import org.buddycloud.channelserver.pubsub.affiliation.Affiliations;
import org.buddycloud.channelserver.pubsub.model.NodeAffiliation;
import org.buddycloud.channelserver.pubsub.model.NodeItem;
import org.buddycloud.channelserver.pubsub.model.NodeSubscription;
import org.buddycloud.channelserver.pubsub.subscription.Subscriptions;
import org.buddycloud.channelserver.utils.node.NodeAclRefuseReason;
import org.buddycloud.channelserver.utils.node.NodeViewAcl;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.dom4j.dom.DOMElement;
import org.dom4j.io.SAXReader;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.PacketError.Condition;
import org.xmpp.packet.PacketError.Type;
import org.xmpp.resultsetmanagement.ResultSet;

public class ItemsGet implements PubSubElementProcessor {
	private static final Logger LOGGER = Logger.getLogger(ItemsGet.class);

	private static final int MAX_ITEMS_TO_RETURN = 50;

	private final BlockingQueue<Packet> outQueue;

	private ChannelManager channelManager;
	private String node;
	private String firstItem;
	private String lastItem;
	private SAXReader xmlReader;
	private Element entry;
	private IQ requestIq;
	private JID fetchersJid;
	private IQ reply;
	private Element resultSetManagement;
	private Element element;

	private NodeViewAcl nodeViewAcl;
	private Map<String, String> nodeDetails;

	public ItemsGet(BlockingQueue<Packet> outQueue,
			ChannelManager channelManager) {
		this.outQueue = outQueue;
		setChannelManager(channelManager);
	}

	public void setChannelManager(ChannelManager ds) {
		channelManager = ds;
	}

	public void setNodeViewAcl(NodeViewAcl acl) {
		nodeViewAcl = acl;
	}

	private NodeViewAcl getNodeViewAcl() {
		if (null == nodeViewAcl) {
			nodeViewAcl = new NodeViewAcl();
		}
		return nodeViewAcl;
	}

	@Override
	public void process(Element elm, JID actorJID, IQ reqIQ, Element rsm)
			throws Exception {
		node = elm.attributeValue("node");
		requestIq = reqIQ;
		reply = IQ.createResultIQ(reqIQ);
		element = elm;
		resultSetManagement = rsm;

		if (false == ResultSet.isValidRSMRequest(resultSetManagement)) {
			resultSetManagement = null;
		}

		if ((node == null) || (true == node.equals(""))) {
			missingNodeIdRequest();
			outQueue.put(reply);
			return;
		}

		fetchersJid = requestIq.getFrom();

		if (false == channelManager.isLocalNode(node)) {
			makeRemoteRequest();
		    return;
		}
		
		try {
			if (false == nodeExists()) {
				setErrorCondition(PacketError.Type.cancel,
						PacketError.Condition.item_not_found);

				outQueue.put(reply);
				return;
			}

			// boolean isLocalNode = channelManager.isLocalNode(node);
			// boolean isLocalSubscriber = false;

			if (actorJID != null) {
				fetchersJid = actorJID;
			}

			if (false == userCanViewNode()) {
				outQueue.put(reply);
				return;
			}
			getItems();
		} catch (NodeStoreException e) {
			setErrorCondition(PacketError.Type.wait,
					PacketError.Condition.internal_server_error);
		}
		outQueue.put(reply);
	}

	private void makeRemoteRequest() throws InterruptedException {
		requestIq.setTo(new JID(node.split("/")[2]).getDomain());
		Element actor = requestIq.getElement()
		    .element("pubsub")
		    .addElement("actor", JabberPubsub.NS_BUDDYCLOUD);
		actor.addText(requestIq.getFrom().toBareJID());
	    outQueue.put(requestIq);
	}

	private boolean nodeExists() throws NodeStoreException {

		if (true == channelManager.nodeExists(node)) {
			nodeDetails = channelManager.getNodeConf(node);
			return true;
		}
		setErrorCondition(PacketError.Type.cancel,
				PacketError.Condition.item_not_found);
		return false;
	}

	private void setErrorCondition(Type type, Condition condition) {
		reply.setType(IQ.Type.error);
		PacketError error = new PacketError(condition, type);
		reply.setError(error);
	}

	private void getItems() throws Exception {
		Element pubsub = new DOMElement(PubSubGet.ELEMENT_NAME,
				new org.dom4j.Namespace("", JabberPubsub.NAMESPACE_URI));

		Element items = pubsub.addElement("items");
		items.addAttribute("node", node);

		xmlReader = new SAXReader();
		entry     = null;
		Element rsmElement;

		if (node.substring(node.length() - 13).equals("subscriptions")) {
			rsmElement = getSubscriptionItems(items);
		} else {
			rsmElement = getNodeItems(items);
		}
		pubsub.add(rsmElement);
		reply.setChildElement(pubsub);
	}

	private boolean userCanViewNode() throws NodeStoreException {
		NodeSubscription nodeSubscription = channelManager.getUserSubscription(
				node, fetchersJid);
		NodeAffiliation nodeAffiliation = channelManager.getUserAffiliation(
				node, fetchersJid);

		Affiliations possibleExistingAffiliation = Affiliations.none;
		Subscriptions possibleExistingSubscription = Subscriptions.none;
		if (null != nodeSubscription) {
			if (null != nodeAffiliation.getAffiliation()) {
				possibleExistingAffiliation = nodeAffiliation.getAffiliation();
			}
			if (null != nodeSubscription.getSubscription()) {
				possibleExistingSubscription = nodeSubscription
						.getSubscription();
			}
		}
		if (true == getNodeViewAcl().canViewNode(node,
				possibleExistingAffiliation, possibleExistingSubscription,
				getNodeAccessModel())) {
			return true;
		}
		NodeAclRefuseReason reason = getNodeViewAcl().getReason();
		createExtendedErrorReply(reason.getType(), reason.getCondition(),
				reason.getAdditionalErrorElement());
		return false;
	}

	private AccessModels getNodeAccessModel() {
		if (false == nodeDetails.containsKey(AccessModel.FIELD_NAME)) {
			return AccessModels.authorize;
		}
		return AccessModels.createFromString(nodeDetails
				.get(AccessModel.FIELD_NAME));
	}

	private void handleForeignNode(boolean isLocalSubscriber)
			throws InterruptedException {
		if (isLocalSubscriber) {

			// TODO, WORK HERE!

			// Start process to fetch items from nodes.
			// Subscribe sub = Subscribe.buildSubscribeStatemachine(node,
			// requestIq, channelManager);
			// outQueue.put(sub.nextStep());
			// return;
		}

		IQ reply = IQ.createResultIQ(requestIq);
		reply.setType(IQ.Type.error);
		PacketError pe = new PacketError(
				org.xmpp.packet.PacketError.Condition.item_not_found,
				org.xmpp.packet.PacketError.Type.cancel);
		reply.setError(pe);
		outQueue.put(reply);
		return;
	}

	/**
	 * Get items for !/subscriptions nodes
	 */
	private Element getNodeItems(Element items) throws NodeStoreException {
        
        ResultSet<NodeItem> nodeItems = channelManager.getNodeItems(node);
        List<NodeItem> rsmNodeItems = nodeItems.applyRSMDirectives(resultSetManagement);

		for (NodeItem nodeItem : rsmNodeItems) {
			try {
				entry = xmlReader.read(
						new StringReader(nodeItem.getPayload()))
						.getRootElement();
				Element item = items.addElement("item");
				item.addAttribute("id", nodeItem.getId());
				item.add(entry);
			} catch (DocumentException e) {
				LOGGER.error("Error parsing a node entry, ignoring. "
						+ nodeItem);
			}
		}
		return nodeItems.generateSetElementFromResults(rsmNodeItems);
	}

	/**
	 * Get items for the /subscriptions node
	 */
	private Element getSubscriptionItems(Element items) throws NodeStoreException {

		ResultSet<NodeSubscription> subscribers = channelManager
				.getNodeSubscriptions(node);
		List<NodeSubscription> rsmSubscribers = subscribers
		    .applyRSMDirectives(resultSetManagement);
		
		Element jidItem;
		Element query;

		for (NodeSubscription subscriber : rsmSubscribers) {
    
			jidItem = items.addElement("item");
			jidItem.addAttribute("id", subscriber.getUser().toString());
			query = jidItem.addElement("query");
			query.addNamespace("", JabberPubsub.NS_DISCO_ITEMS);
			addSubscriptionItems(query, subscriber.getUser());
		}
		return subscribers.generateSetElementFromResults(rsmSubscribers);
	}

	private void addSubscriptionItems(Element query, JID subscriber)
			throws NodeStoreException {

		ResultSet<NodeSubscription> subscriptions = channelManager
				.getUserSubscriptions(subscriber);
		
		if ((null == subscriptions) || (0 == subscriptions.size())) {
			return;
		}
		Element item;
		Namespace ns1 = new Namespace("ns1", JabberPubsub.NAMESPACE_URI);
		Namespace ns2 = new Namespace("ns2", JabberPubsub.NAMESPACE_URI);
        // TODO: This whole section of code is very inefficient
		for (NodeSubscription subscription : subscriptions) {
			////if (false == subscription.getNodeId().contains(fetchersJid.toBareJID())) {
			//	continue;
			//}			
			NodeAffiliation affiliation = channelManager.getUserAffiliation(
					subscription.getNodeId(), subscription.getUser());
			item = query.addElement("item");
			item.add(ns1);
			item.add(ns2);
			item.addAttribute("jid", subscriber.toBareJID());
			item.addAttribute("node", subscription.getNodeId());
			QName affiliationAttribute = new QName("affiliation", ns1);
			QName subscriptionAttribute = new QName("subscription", ns2);
			item.addAttribute(affiliationAttribute, affiliation.getAffiliation()
					.toString());
			item.addAttribute(subscriptionAttribute, subscription.getSubscription()
					.toString());
		}
	}

	private void missingNodeIdRequest() {
		createExtendedErrorReply(PacketError.Type.modify,
				PacketError.Condition.bad_request, "nodeid-required");
	}

	private void createExtendedErrorReply(Type type, Condition condition,
			String additionalElement) {
		reply.setType(IQ.Type.error);
		Element standardError = new DOMElement(condition.toString(),
				new org.dom4j.Namespace("", JabberPubsub.NS_XMPP_STANZAS));
		Element extraError = new DOMElement(additionalElement,
				new org.dom4j.Namespace("", JabberPubsub.NS_PUBSUB_ERROR));
		Element error = new DOMElement("error");
		error.addAttribute("type", type.toString());
		error.add(standardError);
		error.add(extraError);
		reply.setChildElement(error);
	}

	public boolean accept(Element elm) {
		return elm.getName().equals("items");
	}
}