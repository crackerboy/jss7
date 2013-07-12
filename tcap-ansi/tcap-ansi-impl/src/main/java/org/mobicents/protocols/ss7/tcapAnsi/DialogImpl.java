/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2012, Telestax Inc and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.ss7.tcapAnsi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.mobicents.protocols.asn.AsnOutputStream;
import org.mobicents.protocols.ss7.sccp.parameter.SccpAddress;
import org.mobicents.protocols.ss7.tcapAnsi.api.TCAPException;
import org.mobicents.protocols.ss7.tcapAnsi.api.TCAPSendException;
import org.mobicents.protocols.ss7.tcapAnsi.api.TCAPStack;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.ApplicationContext;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.DialogPortion;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.EncodeException;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.ProtocolVersion;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.UserInformation;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.Component;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.ComponentType;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.PAbortCause;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.Reject;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.RejectProblem;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.TCAbortMessage;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.TCQueryMessage;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.TCConversationMessage;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.TCResponseMessage;
import org.mobicents.protocols.ss7.tcapAnsi.api.asn.comp.TCUniMessage;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.component.InvokeClass;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.component.OperationState;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.Dialog;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.TRPseudoState;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.events.TCQueryRequest;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.events.TCConversationRequest;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.events.TCResponseRequest;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.events.TCUniRequest;
import org.mobicents.protocols.ss7.tcapAnsi.api.tc.dialog.events.TCUserAbortRequest;
import org.mobicents.protocols.ss7.tcapAnsi.asn.InvokeImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.ReturnResultNotLastImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.ReturnResultLastImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.TCAbortMessageImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.TCQueryMessageImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.TCConversationMessageImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.TCResponseMessageImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.TCUniMessageImpl;
import org.mobicents.protocols.ss7.tcapAnsi.asn.TcapFactory;
import org.mobicents.protocols.ss7.tcapAnsi.asn.Utils;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.DialogPrimitiveFactoryImpl;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.TCQueryIndicationImpl;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.TCConversationIndicationImpl;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.TCResponseIndicationImpl;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.TCPAbortIndicationImpl;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.TCUniIndicationImpl;
import org.mobicents.protocols.ss7.tcapAnsi.tc.dialog.events.TCUserAbortIndicationImpl;

/**
 * @author baranowb
 * @author amit bhayani
 * @author sergey vetyutnev
 *
 */
public class DialogImpl implements Dialog {

    // timeout of remove task after TC_END
    private static final int _REMOVE_TIMEOUT = 30000;

    private static final Logger logger = Logger.getLogger(DialogImpl.class);

    private Object userObject;

    // lock... ech
    protected ReentrantLock dialogLock = new ReentrantLock();

    // values for timer timeouts
    private long removeTaskTimeout = _REMOVE_TIMEOUT;
    private long idleTaskTimeout;

    // sent/received acn, holds last acn/ui.
    private ApplicationContext lastACN;
    private UserInformation lastUI; // optional

    private Long localTransactionIdObject;
    private long localTransactionId;
    private byte[] remoteTransactionId;
    private Long remoteTransactionIdObject;

    private ProtocolVersion protocolVersion;
    private SccpAddress localAddress;
    private SccpAddress remoteAddress;

    private Future idleTimerFuture;
    private boolean idleTimerActionTaken = false;
    private boolean idleTimerInvoked = false;
    private TRPseudoState state = TRPseudoState.Idle;
    private boolean structured = true;
    // invokde ID space :)
    private static final boolean _INVOKEID_TAKEN = true;
    private static final boolean _INVOKEID_FREE = false;
    private static final int _INVOKE_TABLE_SHIFT = 128;

    private boolean[] invokeIDTable = new boolean[256];
    private int freeCount = invokeIDTable.length;
    private int lastInvokeIdIndex = _INVOKE_TABLE_SHIFT;

    // only originating side keeps FSM, see: Q.771 - 3.1.5
    protected InvokeImpl[] operationsSent = new InvokeImpl[invokeIDTable.length];
    protected InvokeImpl[] operationsSentA = new InvokeImpl[invokeIDTable.length];
    private Set<Long> incomingInvokeList = new HashSet<Long>();
    private ScheduledExecutorService executor;

    // scheduled components list
    private List<Component> scheduledComponentList = new ArrayList<Component>();
    private TCAPProviderImpl provider;

    private int seqControl;

    // If the Dialogue Portion is sent in TCBegin message, the first received
    // Continue message should have the Dialogue Portion too
    private boolean dpSentInBegin = false;

    private boolean previewMode = false;
    protected PrevewDialogData prevewDialogData;

    private static int getIndexFromInvokeId(Long l) {
        int tmp = l.intValue();
        return tmp + _INVOKE_TABLE_SHIFT;
    }

    private static Long getInvokeIdFromIndex(int index) {
        int tmp = index - _INVOKE_TABLE_SHIFT;
        return new Long(tmp);
    }

    /**
     * Creating a Dialog for normal mode
     *
     * @param localAddress
     * @param remoteAddress
     * @param origTransactionId
     * @param structured
     * @param executor
     * @param provider
     * @param seqControl
     * @param previewMode
     */
    protected DialogImpl(SccpAddress localAddress, SccpAddress remoteAddress, Long origTransactionId, boolean structured,
            ScheduledExecutorService executor, TCAPProviderImpl provider, int seqControl, boolean previewMode) {
        super();
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        if (origTransactionId != null) {
            this.localTransactionIdObject = origTransactionId;
            this.localTransactionId = origTransactionId;
        }
        this.executor = executor;
        this.provider = provider;
        this.structured = structured;

        this.seqControl = seqControl;
        this.previewMode = previewMode;

        TCAPStack stack = this.provider.getStack();
        this.idleTaskTimeout = stack.getDialogIdleTimeout();

        // start
        startIdleTimer();
    }

    /**
     * Create a Dialog for previewMode
     *
     * @param dialogId
     * @param localAddress
     * @param remoteAddress
     * @param seqControl
     * @param executor
     * @param provider
     * @param pdd
     * @param sideB
     */
    protected DialogImpl(SccpAddress localAddress, SccpAddress remoteAddress, int seqControl,
            ScheduledExecutorService executor, TCAPProviderImpl provider, PrevewDialogData pdd, boolean sideB) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.localTransactionIdObject = pdd.getDialogId();
        this.localTransactionId = pdd.getDialogId();
        this.executor = executor;
        this.provider = provider;
        this.structured = true;

        this.seqControl = seqControl;
        this.previewMode = true;

        TCAPStack stack = this.provider.getStack();
        this.idleTaskTimeout = stack.getDialogIdleTimeout();

        this.prevewDialogData = pdd;
        this.lastACN = pdd.getLastACN();
        if (sideB) {
            if (pdd.getOperationsSentA() != null)
                this.operationsSent = pdd.getOperationsSentA();
            if (pdd.getOperationsSentB() != null)
                this.operationsSentA = pdd.getOperationsSentB();
        } else {
            if (pdd.getOperationsSentA() != null)
                this.operationsSentA = pdd.getOperationsSentA();
            if (pdd.getOperationsSentB() != null)
                this.operationsSent = pdd.getOperationsSentB();
        }

        for (InvokeImpl invoke : this.operationsSent) {
            if (invoke != null) {
                invoke.setDialog(this);
            }
        }
    }

    public void release() {
        if (!this.previewMode) {
            for (int i = 0; i < this.operationsSent.length; i++) {
                InvokeImpl invokeImpl = this.operationsSent[i];
                if (invokeImpl != null) {
                    invokeImpl.setState(OperationState.Idle);
                    // TODO whether to call operationTimedOut or not is still not clear
                    // operationTimedOut(invokeImpl);
                }
            }
        }

        this.setState(TRPseudoState.Expunged);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#getDialogId()
     */
    public Long getLocalDialogId() {

        return localTransactionIdObject;
    }

    /**
     *
     */
    public Long getRemoteDialogId() {
        if (this.remoteTransactionId != null && this.remoteTransactionIdObject == null) {
            this.remoteTransactionIdObject = Utils.decodeTransactionId(this.remoteTransactionId);
        }

        return this.remoteTransactionIdObject;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#getNewInvokeId()
     */
    public Long getNewInvokeId() throws TCAPException {
        try {
            this.dialogLock.lock();
            if (this.freeCount == 0) {
                throw new TCAPException("No free invokeId");
            }

            int tryCnt = 0;
            while (true) {
                if (++this.lastInvokeIdIndex >= this.invokeIDTable.length)
                    this.lastInvokeIdIndex = 0;
                if (this.invokeIDTable[this.lastInvokeIdIndex] == _INVOKEID_FREE) {
                    freeCount--;
                    this.invokeIDTable[this.lastInvokeIdIndex] = _INVOKEID_TAKEN;
                    return getInvokeIdFromIndex(this.lastInvokeIdIndex);
                }
                if (++tryCnt >= 256)
                    throw new TCAPException("No free invokeId");
            }

        } finally {
            this.dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#cancelInvocation (java.lang.Long)
     */
    public boolean cancelInvocation(Long invokeId) throws TCAPException {
        if (this.previewMode)
            return false;

        try {
            this.dialogLock.lock();
            int index = getIndexFromInvokeId(invokeId);
            if (index < 0 || index >= this.operationsSent.length) {
                throw new TCAPException("Wrong invoke id passed.");
            }

            // lookup through send buffer.
            for (index = 0; index < this.scheduledComponentList.size(); index++) {
                Component cr = this.scheduledComponentList.get(index);
                if (cr.getType() == ComponentType.Invoke && cr.getCorrelationId().equals(invokeId)) {
                    // lucky
                    // TCInvokeRequestImpl invoke = (TCInvokeRequestImpl) cr;
                    // there is no notification on cancel?
                    this.scheduledComponentList.remove(index);
                    ((InvokeImpl) cr).stopTimer();
                    ((InvokeImpl) cr).setState(OperationState.Idle);
                    return true;
                }
            }

            return false;
        } finally {
            this.dialogLock.unlock();
        }
    }

    private void freeInvokeId(Long l) {
        try {
            this.dialogLock.lock();
            int index = getIndexFromInvokeId(l);
            if (this.invokeIDTable[index] == _INVOKEID_TAKEN)
                this.freeCount++;
            this.invokeIDTable[index] = _INVOKEID_FREE;
        } finally {
            this.dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#getRemoteAddress()
     */
    public SccpAddress getRemoteAddress() {

        return this.remoteAddress;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#getLocalAddress()
     */
    public SccpAddress getLocalAddress() {

        return this.localAddress;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#isEstabilished()
     */
    public boolean isEstabilished() {

        return this.state == TRPseudoState.Active;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#isStructured()
     */
    public boolean isStructured() {

        return this.structured;
    }

    public void keepAlive() {
        if (this.previewMode)
            return;

        try {
            this.dialogLock.lock();
            if (this.idleTimerInvoked) {
                this.idleTimerActionTaken = true;
            }

        } finally {
            this.dialogLock.unlock();
        }

    }

    @Override
    public ReentrantLock getDialogLock() {
        return this.dialogLock;
    }

    /**
     * @return the acn
     */
    public ApplicationContext getApplicationContextName() {
        return lastACN;
    }

    /**
     * @return the ui
     */
    public UserInformation getUserInformation() {
        return lastUI;
    }

    /**
     * Adding the new incoming invokeId into incomingInvokeList list
     *
     * @param invokeId
     * @return false: failure - this invokeId already present in the list
     */
    private boolean addIncomingInvokeId(Long invokeId) {
        synchronized (this.incomingInvokeList) {
            if (this.incomingInvokeList.contains(invokeId))
                return false;
            else {
                this.incomingInvokeList.add(invokeId);
                return true;
            }
        }
    }

    private void removeIncomingInvokeId(Long invokeId) {
        synchronized (this.incomingInvokeList) {
            this.incomingInvokeList.remove(invokeId);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#send(org.mobicents
     * .protocols.ss7.tcap.api.tc.dialog.events.TCBeginRequest)
     */
    public void send(TCQueryRequest event) throws TCAPSendException {

        if (this.previewMode)
            return;

        if (this.state != TRPseudoState.Idle) {
            throw new TCAPSendException("Can not send Begin in this state: " + this.state);
        }

        if (!this.isStructured()) {
            throw new TCAPSendException("Unstructured dialogs do not use Begin");
        }
        try {
            this.dialogLock.lock();
            this.idleTimerActionTaken = true;
            restartIdleTimer();
            TCQueryMessageImpl tcbm = (TCQueryMessageImpl) TcapFactory.createTCQueryMessage();

            // build DP

            if (event.getApplicationContextName() != null) {
                this.dpSentInBegin = true;
                DialogPortion dp = TcapFactory.createDialogPortion();
//                dp.setUnidirectional(false);
//                DialogRequestAPDU apdu = TcapFactory.createDialogAPDURequest();
//                dp.setDialogAPDU(apdu);
//                apdu.setApplicationContextName(event.getApplicationContextName());
                this.lastACN = event.getApplicationContextName();
                if (event.getUserInformation() != null) {
//                    apdu.setUserInformation(event.getUserInformation());
                    this.lastUI = event.getUserInformation();
                }
                tcbm.setDialogPortion(dp);
            }

            // now comps
            tcbm.setOriginatingTransactionId(Utils.encodeTransactionId(this.localTransactionId));
            if (this.scheduledComponentList.size() > 0) {
                Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
                this.prepareComponents(componentsToSend);
                tcbm.setComponent(componentsToSend);
            }

            AsnOutputStream aos = new AsnOutputStream();
            try {
                tcbm.encode(aos);
                this.setState(TRPseudoState.InitialSent);
                this.provider.send(aos.toByteArray(), event.getReturnMessageOnError(), this.remoteAddress, this.localAddress,
                        this.seqControl);
                this.scheduledComponentList.clear();
            } catch (Throwable e) {
                // FIXME: remove freshly added invokes to free invoke ID??
                // TODO: should we release this dialog because TC-BEGIN sending has been failed
                if (logger.isEnabledFor(Level.ERROR)) {
                    logger.error("Failed to send message: ", e);
                }
                throw new TCAPSendException("Failed to send TC-Begin message: " + e.getMessage(), e);
            }

        } finally {
            this.dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#send(org.mobicents
     * .protocols.ss7.tcap.api.tc.dialog.events.TCContinueRequest)
     */
    public void send(TCConversationRequest event) throws TCAPSendException {

        if (this.previewMode)
            return;

        if (!this.isStructured()) {
            throw new TCAPSendException("Unstructured dialogs do not use Continue");
        }
        try {
            this.dialogLock.lock();
            if (this.state == TRPseudoState.InitialReceived) {
                this.idleTimerActionTaken = true;
                restartIdleTimer();
                TCConversationMessageImpl tcbm = (TCConversationMessageImpl) TcapFactory.createTCConversationMessage();

                if (event.getApplicationContextName() != null) {

                    // set dialog portion
                    DialogPortion dp = TcapFactory.createDialogPortion();
//                    dp.setUnidirectional(false);
//                    DialogResponseAPDU apdu = TcapFactory.createDialogAPDUResponse();
//                    dp.setDialogAPDU(apdu);
//                    apdu.setApplicationContextName(event.getApplicationContextName());
//                    if (event.getUserInformation() != null) {
//                        apdu.setUserInformation(event.getUserInformation());
//                    }
                    // WHERE THE HELL THIS COMES FROM!!!!
                    // WHEN REJECTED IS USED !!!!!
//                    Result res = TcapFactory.createResult();
//                    res.setResultType(ResultType.Accepted);
//                    ResultSourceDiagnostic rsd = TcapFactory.createResultSourceDiagnostic();
//                    rsd.setDialogServiceUserType(DialogServiceUserType.Null);
//                    apdu.setResultSourceDiagnostic(rsd);
//                    apdu.setResult(res);
                    tcbm.setDialogPortion(dp);

                }

                tcbm.setOriginatingTransactionId(Utils.encodeTransactionId(this.localTransactionId));
                tcbm.setDestinationTransactionId(this.remoteTransactionId);
                if (this.scheduledComponentList.size() > 0) {
                    Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
                    this.prepareComponents(componentsToSend);
                    tcbm.setComponent(componentsToSend);

                }
                // local address may change, lets check it;
                if (event.getOriginatingAddress() != null && !event.getOriginatingAddress().equals(this.localAddress)) {
                    this.localAddress = event.getOriginatingAddress();
                }
                AsnOutputStream aos = new AsnOutputStream();
                try {
                    tcbm.encode(aos);
                    this.provider.send(aos.toByteArray(), event.getReturnMessageOnError(), this.remoteAddress,
                            this.localAddress, this.seqControl);
                    this.setState(TRPseudoState.Active);
                    this.scheduledComponentList.clear();
                } catch (Exception e) {
                    // FIXME: remove freshly added invokes to free invoke ID??
                    if (logger.isEnabledFor(Level.ERROR)) {
                        logger.error("Failed to send message: ", e);
                    }
                    throw new TCAPSendException("Failed to send TC-Continue message: " + e.getMessage(), e);
                }

            } else if (state == TRPseudoState.Active) {
                this.idleTimerActionTaken = true;
                restartIdleTimer();
                // in this we ignore acn and passed args(except qos)
                TCConversationMessageImpl tcbm = (TCConversationMessageImpl) TcapFactory.createTCConversationMessage();

                tcbm.setOriginatingTransactionId(Utils.encodeTransactionId(this.localTransactionId));
                tcbm.setDestinationTransactionId(this.remoteTransactionId);
                if (this.scheduledComponentList.size() > 0) {
                    Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
                    this.prepareComponents(componentsToSend);
                    tcbm.setComponent(componentsToSend);

                }

                AsnOutputStream aos = new AsnOutputStream();
                try {
                    tcbm.encode(aos);
                    this.provider.send(aos.toByteArray(), event.getReturnMessageOnError(), this.remoteAddress,
                            this.localAddress, this.seqControl);
                    this.scheduledComponentList.clear();
                } catch (Exception e) {
                    // FIXME: remove freshly added invokes to free invoke ID??
                    if (logger.isEnabledFor(Level.ERROR)) {
                        logger.error("Failed to send message: ", e);
                    }
                    throw new TCAPSendException("Failed to send TC-Continue message: " + e.getMessage(), e);
                }
            } else {
                throw new TCAPSendException("Wrong state: " + this.state);
            }

        } finally {
            this.dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#send(org.mobicents
     * .protocols.ss7.tcap.api.tc.dialog.events.TCEndRequest)
     */
    public void send(TCResponseRequest event) throws TCAPSendException {

        if (this.previewMode)
            return;

        if (!this.isStructured()) {
            throw new TCAPSendException("Unstructured dialogs do not use End");
        }

        try {
            dialogLock.lock();
            TCResponseMessageImpl tcbm = null;

            if (state == TRPseudoState.InitialReceived) {
                // TC-END request primitive issued in response to a TC-BEGIN
                // indication primitive
                this.idleTimerActionTaken = true;
                stopIdleTimer();
                tcbm = (TCResponseMessageImpl) TcapFactory.createTCResponseMessage();
                tcbm.setDestinationTransactionId(this.remoteTransactionId);

                if (this.scheduledComponentList.size() > 0) {
                    Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
                    this.prepareComponents(componentsToSend);
                    tcbm.setComponent(componentsToSend);

                }

                ApplicationContext acn = event.getApplicationContextName();
                if (acn != null) { // acn & DialogPortion is absent in TCAP V1

                    // set dialog portion
                    DialogPortion dp = TcapFactory.createDialogPortion();
//                    dp.setUnidirectional(false);
//                    DialogResponseAPDU apdu = TcapFactory.createDialogAPDUResponse();
//                    dp.setDialogAPDU(apdu);

//                    apdu.setApplicationContextName(event.getApplicationContextName());
//                    if (event.getUserInformation() != null) {
//                        apdu.setUserInformation(event.getUserInformation());
//                    }

                    // WHERE THE HELL THIS COMES FROM!!!!
                    // WHEN REJECTED IS USED !!!!!
//                    Result res = TcapFactory.createResult();
//                    res.setResultType(ResultType.Accepted);
//                    ResultSourceDiagnostic rsd = TcapFactory.createResultSourceDiagnostic();
//                    rsd.setDialogServiceUserType(DialogServiceUserType.Null);
//                    apdu.setResultSourceDiagnostic(rsd);
//                    apdu.setResult(res);
                    tcbm.setDialogPortion(dp);
                }

            } else if (state == TRPseudoState.Active) {
                restartIdleTimer();
                tcbm = (TCResponseMessageImpl) TcapFactory.createTCResponseMessage();

                tcbm.setDestinationTransactionId(this.remoteTransactionId);
                if (this.scheduledComponentList.size() > 0) {
                    Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
                    this.prepareComponents(componentsToSend);
                    tcbm.setComponent(componentsToSend);
                }

                // ITU - T Q774 Section 3.2.2.1 Dialogue Control

                // when a dialogue portion is received inopportunely (e.g. a
                // dialogue APDU is received during the active state of a
                // transaction).

                // Don't set the Application Context or Dialogue Portion in
                // Active state

            } else {
                throw new TCAPSendException(String.format("State is not %s or %s: it is %s", TRPseudoState.Active,
                        TRPseudoState.InitialReceived, this.state));
            }

            AsnOutputStream aos = new AsnOutputStream();
            try {
                tcbm.encode(aos);
                this.provider.send(aos.toByteArray(), event.getReturnMessageOnError(), this.remoteAddress, this.localAddress,
                        this.seqControl);

                this.scheduledComponentList.clear();
            } catch (Exception e) {
                // FIXME: remove freshly added invokes to free invoke ID??
                if (logger.isEnabledFor(Level.ERROR)) {
                    logger.error("Failed to send message: ", e);
                }
                throw new TCAPSendException("Failed to send TC-End message: " + e.getMessage(), e);
            } finally {
                // FIXME: is this proper place - should we not release in case
                // of error ?
                release();
            }
        } finally {
            dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#sendUni()
     */
    public void send(TCUniRequest event) throws TCAPSendException {

        if (this.previewMode)
            return;

        if (this.isStructured()) {
            throw new TCAPSendException("Structured dialogs do not use Uni");
        }

        try {
            this.dialogLock.lock();
            TCUniMessageImpl msg = (TCUniMessageImpl) TcapFactory.createTCUniMessage();

            if (event.getApplicationContextName() != null) {
                DialogPortion dp = TcapFactory.createDialogPortion();
//                DialogUniAPDU apdu = TcapFactory.createDialogAPDUUni();
//                apdu.setApplicationContextName(event.getApplicationContextName());
//                if (event.getUserInformation() != null) {
//                    apdu.setUserInformation(event.getUserInformation());
//                }
//                dp.setUnidirectional(true);
//                dp.setDialogAPDU(apdu);
                msg.setDialogPortion(dp);

            }

            if (this.scheduledComponentList.size() > 0) {
                Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
                this.prepareComponents(componentsToSend);
                msg.setComponent(componentsToSend);

            }

            AsnOutputStream aos = new AsnOutputStream();
            try {
                msg.encode(aos);
                this.provider.send(aos.toByteArray(), event.getReturnMessageOnError(), this.remoteAddress, this.localAddress,
                        this.seqControl);
                this.scheduledComponentList.clear();
            } catch (Exception e) {
                if (logger.isEnabledFor(Level.ERROR)) {
                    logger.error("Failed to send message: ", e);
                }
                throw new TCAPSendException("Failed to send TC-Uni message: " + e.getMessage(), e);
            } finally {
                release();
            }
        } finally {
            this.dialogLock.unlock();
        }
    }

    public void send(TCUserAbortRequest event) throws TCAPSendException {

        if (this.previewMode)
            return;

        // is abort allowed in "Active" state ?
        if (!isStructured()) {
            throw new TCAPSendException("Unstructured dialog can not be aborted!");
        }

        try {
            this.dialogLock.lock();

            if (this.state == TRPseudoState.InitialReceived || this.state == TRPseudoState.InitialSent
                    || this.state == TRPseudoState.Active) {
                // allowed
                DialogPortion dp = null;
                if (event.getUserInformation() != null /*|| event.getDialogServiceUserType() != null*/) {
                    // User information can be absent in TCAP V1

                    dp = TcapFactory.createDialogPortion();
//                    dp.setUnidirectional(false);

//                    if (event.getDialogServiceUserType() != null) {
//                        // ITU T Q.774 Read Dialogue end on page 12 and 3.2.2
//                        // Abnormal
//                        // procedures on page 13 and 14
//                        DialogResponseAPDU apdu = TcapFactory.createDialogAPDUResponse();
//                        apdu.setApplicationContextName(event.getApplicationContextName());
//                        apdu.setUserInformation(event.getUserInformation());
//
//                        Result res = TcapFactory.createResult();
//                        res.setResultType(ResultType.RejectedPermanent);
//                        ResultSourceDiagnostic rsd = TcapFactory.createResultSourceDiagnostic();
//                        rsd.setDialogServiceUserType(event.getDialogServiceUserType());
//                        apdu.setResultSourceDiagnostic(rsd);
//                        apdu.setResult(res);
//                        dp.setDialogAPDU(apdu);
//                    } else {
//                        // When a BEGIN message has been received (i.e. the
//                        // dialogue
//                        // is
//                        // in the "Initiation Received" state) containing a
//                        // Dialogue
//                        // Request (AARQ) APDU, the TC-User can abort for any
//                        // user
//                        // defined reason. In such a situation, the TC-User
//                        // issues a
//                        // TC-U-ABORT request primitive with the Abort Reason
//                        // parameter
//                        // absent or with set to any value other than either
//                        // "application-context-name-not-supported" or
//                        // dialogue-refused". In such a case, a Dialogue Abort (ABRT) APDU is generated with abort-source coded as "dialogue-service-user",
//                        // and supplied as the User Data parameter of the
//                        // TR-U-ABORT
//                        // request primitive. User information (if any) provided
//                        // in
//                        // the
//                        // TC-U-ABORT request primitive is coded in the
//                        // user-information
//                        // field of the ABRT APDU.
//                        DialogAbortAPDU dapdu = TcapFactory.createDialogAPDUAbort();
//
//                        AbortSource as = TcapFactory.createAbortSource();
//                        as.setAbortSourceType(AbortSourceType.User);
//                        dapdu.setAbortSource(as);
//                        dapdu.setUserInformation(event.getUserInformation());
//                        dp.setDialogAPDU(dapdu);
//                    }
                }

                TCAbortMessageImpl msg = (TCAbortMessageImpl) TcapFactory.createTCAbortMessage();
                msg.setDestinationTransactionId(this.remoteTransactionId);
                msg.setDialogPortion(dp);

                // no components
                AsnOutputStream aos = new AsnOutputStream();
                try {
                    msg.encode(aos);
                    this.provider.send(aos.toByteArray(), event.getReturnMessageOnError(), this.remoteAddress,
                            this.localAddress, this.seqControl);

                    this.scheduledComponentList.clear();
                } catch (Exception e) {
                    // FIXME: remove freshly added invokes to free invoke ID??
                    if (logger.isEnabledFor(Level.ERROR)) {
                        e.printStackTrace();
                        logger.error("Failed to send message: ", e);
                    }
                    throw new TCAPSendException("Failed to send TC-U-Abort message: " + e.getMessage(), e);
                } finally {
                    release();
                }
            }
        } finally {
            this.dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#sendComponent(org
     * .mobicents.protocols.ss7.tcap.api.tc.component.ComponentRequest)
     */
    public void sendComponent(Component componentRequest) throws TCAPSendException {

        if (this.previewMode)
            return;

        try {
            this.dialogLock.lock();
            if (componentRequest.getType() == ComponentType.Invoke) {
                InvokeImpl invoke = (InvokeImpl) componentRequest;

                // check if its taken!
                int invokeIndex = this.getIndexFromInvokeId(invoke.getInvokeId());
                if (this.operationsSent[invokeIndex] != null) {
                    throw new TCAPSendException("There is already operation with such invoke id!");
                }

                invoke.setState(OperationState.Pending);
                invoke.setDialog(this);

                // if the Invoke timeout value has not be reset by TCAP-User
                // for this invocation we are setting it to the the TCAP stack
                // default value
                if (invoke.getTimeout() == TCAPStackImpl._EMPTY_INVOKE_TIMEOUT)
                    invoke.setTimeout(this.provider.getStack().getInvokeTimeout());
            } else {
                if (componentRequest.getType() != ComponentType.ReturnResultNotLast) {
                    // we are sending a response and removing invokeId from
                    // incomingInvokeList
                    this.removeIncomingInvokeId(componentRequest.getCorrelationId());
                }
            }
            this.scheduledComponentList.add(componentRequest);
        } finally {
            this.dialogLock.unlock();
        }
    }

    public void processInvokeWithoutAnswer(Long invokeId) {
        if (this.previewMode)
            return;

        this.removeIncomingInvokeId(invokeId);
    }

    private void prepareComponents(Component[] res) {

        int index = 0;
        while (this.scheduledComponentList.size() > index) {
            Component cr = this.scheduledComponentList.get(index);
            if (cr.getType() == ComponentType.Invoke) {
                InvokeImpl in = (InvokeImpl) cr;
                // FIXME: check not null?
                this.operationsSent[this.getIndexFromInvokeId(in.getInvokeId())] = in;
                in.setState(OperationState.Sent);
            }

            res[index++] = cr;
        }
    }

    public int getMaxUserDataLength() {

        return this.provider.getMaxUserDataLength(remoteAddress, localAddress);
    }

    public int getDataLength(TCQueryRequest event) throws TCAPSendException {

        TCQueryMessageImpl tcbm = (TCQueryMessageImpl) TcapFactory.createTCQueryMessage();

        if (event.getApplicationContextName() != null) {
            DialogPortion dp = TcapFactory.createDialogPortion();
//            dp.setUnidirectional(false);
//            DialogRequestAPDU apdu = TcapFactory.createDialogAPDURequest();
//            dp.setDialogAPDU(apdu);
//            apdu.setApplicationContextName(event.getApplicationContextName());
//            if (event.getUserInformation() != null) {
//                apdu.setUserInformation(event.getUserInformation());
//            }
            tcbm.setDialogPortion(dp);
        }

        // now comps
        tcbm.setOriginatingTransactionId(Utils.encodeTransactionId(this.localTransactionId));
        if (this.scheduledComponentList.size() > 0) {
            Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
            for (int index = 0; index < this.scheduledComponentList.size(); index++) {
                componentsToSend[index] = this.scheduledComponentList.get(index);
            }
            tcbm.setComponent(componentsToSend);
        }

        AsnOutputStream aos = new AsnOutputStream();
        try {
            tcbm.encode(aos);
        } catch (EncodeException e) {
            if (logger.isEnabledFor(Level.ERROR)) {
                logger.error("Failed to encode message while length testing: ", e);
            }
            throw new TCAPSendException("Error encoding TCBeginRequest", e);
        }
        return aos.size();
    }

    public int getDataLength(TCConversationRequest event) throws TCAPSendException {

        TCConversationMessageImpl tcbm = (TCConversationMessageImpl) TcapFactory.createTCConversationMessage();

        if (event.getApplicationContextName() != null) {

            // set dialog portion
            DialogPortion dp = TcapFactory.createDialogPortion();
//            dp.setUnidirectional(false);
//            DialogResponseAPDU apdu = TcapFactory.createDialogAPDUResponse();
//            dp.setDialogAPDU(apdu);
//            apdu.setApplicationContextName(event.getApplicationContextName());
//            if (event.getUserInformation() != null) {
//                apdu.setUserInformation(event.getUserInformation());
//            }
            // WHERE THE HELL THIS COMES FROM!!!!
            // WHEN REJECTED IS USED !!!!!
//            Result res = TcapFactory.createResult();
//            res.setResultType(ResultType.Accepted);
//            ResultSourceDiagnostic rsd = TcapFactory.createResultSourceDiagnostic();
//            rsd.setDialogServiceUserType(DialogServiceUserType.Null);
//            apdu.setResultSourceDiagnostic(rsd);
//            apdu.setResult(res);
            tcbm.setDialogPortion(dp);

        }

        tcbm.setOriginatingTransactionId(Utils.encodeTransactionId(this.localTransactionId));
        tcbm.setDestinationTransactionId(this.remoteTransactionId);
        if (this.scheduledComponentList.size() > 0) {
            Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
            for (int index = 0; index < this.scheduledComponentList.size(); index++) {
                componentsToSend[index] = this.scheduledComponentList.get(index);
            }
            tcbm.setComponent(componentsToSend);
        }

        AsnOutputStream aos = new AsnOutputStream();
        try {
            tcbm.encode(aos);
        } catch (Exception e) {
            if (logger.isEnabledFor(Level.ERROR)) {
                logger.error("Failed to encode message while length testing: ", e);
            }
            throw new TCAPSendException("Error encoding TCContinueRequest", e);
        }

        return aos.size();
    }

    public int getDataLength(TCResponseRequest event) throws TCAPSendException {

        // TC-END request primitive issued in response to a TC-BEGIN
        // indication primitive
        TCResponseMessageImpl tcbm = (TCResponseMessageImpl) TcapFactory.createTCResponseMessage();
        tcbm.setDestinationTransactionId(this.remoteTransactionId);

        if (this.scheduledComponentList.size() > 0) {
            Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
            for (int index = 0; index < this.scheduledComponentList.size(); index++) {
                componentsToSend[index] = this.scheduledComponentList.get(index);
            }
            tcbm.setComponent(componentsToSend);
        }

        if (state == TRPseudoState.InitialReceived) {
            ApplicationContext acn = event.getApplicationContextName();
            if (acn != null) { // acn & DialogPortion is absent in TCAP V1

                // set dialog portion
                DialogPortion dp = TcapFactory.createDialogPortion();
//                dp.setUnidirectional(false);
//                DialogResponseAPDU apdu = TcapFactory.createDialogAPDUResponse();
//                dp.setDialogAPDU(apdu);
//
//                apdu.setApplicationContextName(event.getApplicationContextName());
//                if (event.getUserInformation() != null) {
//                    apdu.setUserInformation(event.getUserInformation());
//                }

                // WHERE THE HELL THIS COMES FROM!!!!
                // WHEN REJECTED IS USED !!!!!
//                Result res = TcapFactory.createResult();
//                res.setResultType(ResultType.Accepted);
//                ResultSourceDiagnostic rsd = TcapFactory.createResultSourceDiagnostic();
//                rsd.setDialogServiceUserType(DialogServiceUserType.Null);
//                apdu.setResultSourceDiagnostic(rsd);
//                apdu.setResult(res);
                tcbm.setDialogPortion(dp);
            }
        }

        AsnOutputStream aos = new AsnOutputStream();
        try {
            tcbm.encode(aos);
        } catch (Exception e) {
            if (logger.isEnabledFor(Level.ERROR)) {
                logger.error("Failed to encode message while length testing: ", e);
            }
            throw new TCAPSendException("Error encoding TCEndRequest", e);
        }

        return aos.size();
    }

    public int getDataLength(TCUniRequest event) throws TCAPSendException {

        TCUniMessageImpl msg = (TCUniMessageImpl) TcapFactory.createTCUniMessage();

        if (event.getApplicationContextName() != null) {
            DialogPortion dp = TcapFactory.createDialogPortion();
//            DialogUniAPDU apdu = TcapFactory.createDialogAPDUUni();
//            apdu.setApplicationContextName(event.getApplicationContextName());
//            if (event.getUserInformation() != null) {
//                apdu.setUserInformation(event.getUserInformation());
//            }
//            dp.setUnidirectional(true);
//            dp.setDialogAPDU(apdu);
            msg.setDialogPortion(dp);

        }

        if (this.scheduledComponentList.size() > 0) {
            Component[] componentsToSend = new Component[this.scheduledComponentList.size()];
            for (int index = 0; index < this.scheduledComponentList.size(); index++) {
                componentsToSend[index] = this.scheduledComponentList.get(index);
            }
            msg.setComponent(componentsToSend);

        }

        AsnOutputStream aos = new AsnOutputStream();
        try {
            msg.encode(aos);
        } catch (Exception e) {
            if (logger.isEnabledFor(Level.ERROR)) {
                logger.error("Failed to encode message while length testing: ", e);
            }
            throw new TCAPSendException("Error encoding TCUniRequest", e);
        }

        return aos.size();
    }

    // /////////////////
    // LOCAL METHODS //
    // /////////////////

    @Override
    public ProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public void setProtocolVersion(ProtocolVersion protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /**
     * @param remoteTransactionId the remoteTransactionId to set
     */
    void setRemoteTransactionId(byte[] remoteTransactionId) {
        this.remoteTransactionId = remoteTransactionId;
    }

    /**
     * @param localAddress the localAddress to set
     */
    public void setLocalAddress(SccpAddress localAddress) {
        this.localAddress = localAddress;
    }

    /**
     * @param remoteAddress the remoteAddress to set
     */
    public void setRemoteAddress(SccpAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    void processUni(TCUniMessage msg, SccpAddress localAddress, SccpAddress remoteAddress) {

        // TCUniIndicationImpl tcUniIndication = null;
        try {
            this.dialogLock.lock();
            // this is invoked ONLY for server.
            // if (state != TRPseudoState.Idle) {
            // // should we terminate dialog here?
            // if (logger.isEnabledFor(Level.ERROR)) {
            // logger.error("Received Uni primitive, but state is not: " +
            // TRPseudoState.Idle + ". Dialog: " + this);
            // }
            // return;
            // // throw new
            // TCAPException("Received Uni primitive, but state is not: " +
            // TRPseudoState.Idle + ". Dialog: " + this);
            // }
            // lets setup
            this.setRemoteAddress(remoteAddress);
            this.setLocalAddress(localAddress);

            // no dialog portion!
            // convert to indications
            TCUniIndicationImpl tcUniIndication = (TCUniIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
                    .getDialogPrimitiveFactory()).createUniIndication(this);

            tcUniIndication.setDestinationAddress(localAddress);
            tcUniIndication.setOriginatingAddress(remoteAddress);
            // now comps
            Component[] comps = msg.getComponent();
            tcUniIndication.setComponents(comps);

            if (msg.getDialogPortion() != null) {
                // it should be dialog req?
                DialogPortion dp = msg.getDialogPortion();
//                DialogUniAPDU apdu = (DialogUniAPDU) dp.getDialogAPDU();
//                this.lastACN = apdu.getApplicationContextName();
//                this.lastUI = apdu.getUserInformation();
                tcUniIndication.setApplicationContextName(this.lastACN);
                tcUniIndication.setUserInformation(this.lastUI);
            }

            // lets deliver to provider, this MUST not throw anything
            this.provider.deliver(this, tcUniIndication);
            // schedule removal
            // this.release();

        } finally {
            this.release();
            this.dialogLock.unlock();
        }
    }

    protected void processQuery(TCQueryMessage msg, SccpAddress localAddress, SccpAddress remoteAddress, boolean dialogTermitationPermission) {

        TCQueryIndicationImpl tcBeginIndication = null;
        try {
            this.dialogLock.lock();

            if (!this.previewMode) {
                // this is invoked ONLY for server.
                if (state != TRPseudoState.Idle) {
                    // should we terminate dialog here?
                    if (logger.isEnabledFor(Level.ERROR)) {
                        logger.error("Received Begin primitive, but state is not: " + TRPseudoState.Idle + ". Dialog: " + this);
                    }
                    this.sendAbnormalDialog();
                    return;
                }
                restartIdleTimer();
            }

            // lets setup
            this.setRemoteAddress(remoteAddress);
            this.setLocalAddress(localAddress);
            this.setRemoteTransactionId(msg.getOriginatingTransactionId());
            // convert to indications
            tcBeginIndication = (TCQueryIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider.getDialogPrimitiveFactory()).createQueryIndication(this,
                    dialogTermitationPermission);

            tcBeginIndication.setDestinationAddress(localAddress);
            tcBeginIndication.setOriginatingAddress(remoteAddress);

            // if APDU and context data present, lets store it
            DialogPortion dialogPortion = msg.getDialogPortion();

            if (dialogPortion != null) {
                // this should not be null....
//                DialogAPDU apdu = dialogPortion.getDialogAPDU();
//                if (apdu.getType() != DialogAPDUType.Request) {
//                    if (logger.isEnabledFor(Level.ERROR)) {
//                        logger.error("Received non-Request APDU: " + apdu.getType() + ". Dialog: " + this);
//                    }
//                    this.sendAbnormalDialog();
//                    return;
//                }
//                DialogRequestAPDU requestAPDU = (DialogRequestAPDU) apdu;
//                this.lastACN = requestAPDU.getApplicationContextName();
//                this.lastUI = requestAPDU.getUserInformation();
                tcBeginIndication.setApplicationContextName(this.lastACN);
                tcBeginIndication.setUserInformation(this.lastUI);
            }
            tcBeginIndication.setComponents(processOperationsState(msg.getComponent()));
            if (!this.previewMode) {
                // change state - before we deliver
                this.setState(TRPseudoState.InitialReceived);
            }
            // lets deliver to provider
            this.provider.deliver(this, tcBeginIndication);

        } finally {
            this.dialogLock.unlock();
        }
    }

    protected void processConversation(TCConversationMessage msg, SccpAddress localAddress, SccpAddress remoteAddressm, boolean dialogTermitationPermission) {

        TCConversationIndicationImpl tcContinueIndication = null;
        try {
            this.dialogLock.lock();

            if (this.previewMode) {
                tcContinueIndication = (TCConversationIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider.getDialogPrimitiveFactory())
                        .createConversationIndication(this, dialogTermitationPermission);
                this.setRemoteTransactionId(msg.getOriginatingTransactionId());

                // here we will receive DialogResponse APDU - if request was
                // present!
                DialogPortion dialogPortion = msg.getDialogPortion();
                if (dialogPortion != null) {
                    // this should not be null....
//                    DialogAPDU apdu = dialogPortion.getDialogAPDU();
//                    if (apdu.getType() == DialogAPDUType.Response) {
//                        DialogResponseAPDU responseAPDU = (DialogResponseAPDU) apdu;
//                        // this will be present if APDU is present.
//                        if (!responseAPDU.getApplicationContextName().equals(this.lastACN)) {
//                            this.lastACN = responseAPDU.getApplicationContextName();
//                        }
//                        if (responseAPDU.getUserInformation() != null) {
//                            this.lastUI = responseAPDU.getUserInformation();
//                        }
//                        tcContinueIndication.setApplicationContextName(responseAPDU.getApplicationContextName());
//                        tcContinueIndication.setUserInformation(responseAPDU.getUserInformation());
//                    }
                }
                tcContinueIndication.setOriginatingAddress(remoteAddress);
                // now comps
                tcContinueIndication.setComponents(processOperationsState(msg.getComponent()));

                // lets deliver to provider
                this.provider.deliver(this, tcContinueIndication);
            } else {

                if (state == TRPseudoState.InitialSent) {
                    restartIdleTimer();
                    tcContinueIndication = (TCConversationIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider.getDialogPrimitiveFactory())
                            .createConversationIndication(this, dialogTermitationPermission);
                    // in continue remote address MAY change be cjanged, so lets
                    // update!
                    this.setRemoteAddress(remoteAddress);
                    this.setRemoteTransactionId(msg.getOriginatingTransactionId());
                    tcContinueIndication.setOriginatingAddress(remoteAddress);

                    // here we will receive DialogResponse APDU - if request was
                    // present!
                    DialogPortion dialogPortion = msg.getDialogPortion();
                    if (dialogPortion != null) {
                        // this should not be null....
//                        DialogAPDU apdu = dialogPortion.getDialogAPDU();
//                        if (apdu.getType() != DialogAPDUType.Response) {
//                            if (logger.isEnabledFor(Level.ERROR)) {
//                                logger.error("Received non-Response APDU: " + apdu.getType() + ". Dialog: " + this);
//                            }
//                            this.sendAbnormalDialog();
//                            return;
//                        }
//                        DialogResponseAPDU responseAPDU = (DialogResponseAPDU) apdu;
                        // this will be present if APDU is present.
//                        if (!responseAPDU.getApplicationContextName().equals(this.lastACN)) {
//                            this.lastACN = responseAPDU.getApplicationContextName();
//                        }
//                        if (responseAPDU.getUserInformation() != null) {
//                            this.lastUI = responseAPDU.getUserInformation();
//                        }
//                        tcContinueIndication.setApplicationContextName(responseAPDU.getApplicationContextName());
//                        tcContinueIndication.setUserInformation(responseAPDU.getUserInformation());
                    } else if (this.dpSentInBegin) {
                        // ITU - T Q.774 3.2.2 : Abnormal procedure page 13

                        // when a dialogue portion is missing when its presence
                        // is
                        // mandatory (e.g. an AARQ APDU was sent in a Begin
                        // message,
                        // but
                        // no AARE APDU was received in the first backward
                        // Continue
                        // message) or when a dialogue portion is received
                        // inopportunely
                        // (e.g. a dialogue APDU is received during the active
                        // state
                        // of
                        // a transaction). At the side where the abnormality is
                        // detected, a TC-P-ABORT indication primitive is issued
                        // to
                        // the
                        // local TC-user with the "P-Abort" parameter in the
                        // primitive
                        // set to "abnormal dialogue". At the same time, a
                        // TR-U-ABORT
                        // request primitive is issued to the transaction
                        // sub-layer
                        // with
                        // an ABRT APDU as user data. The abort-source field of
                        // the
                        // ABRT
                        // APDU is set to "dialogue-service-provider" and the
                        // user
                        // information field is absent.

                        sendAbnormalDialog();
                        return;

                    }
                    tcContinueIndication.setOriginatingAddress(remoteAddress);
                    // now comps
                    tcContinueIndication.setComponents(processOperationsState(msg.getComponent()));
                    // change state
                    this.setState(TRPseudoState.Active);

                    // lets deliver to provider
                    this.provider.deliver(this, tcContinueIndication);

                } else if (state == TRPseudoState.Active) {
                    restartIdleTimer();
                    // XXX: here NO APDU will be present, hence, no ACN/UI change
                    tcContinueIndication = (TCConversationIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider.getDialogPrimitiveFactory())
                            .createConversationIndication(this, dialogTermitationPermission);

                    tcContinueIndication.setOriginatingAddress(remoteAddress);

                    // now comps
                    tcContinueIndication.setComponents(processOperationsState(msg.getComponent()));

                    // lets deliver to provider
                    this.provider.deliver(this, tcContinueIndication);

                } else {
                    if (logger.isEnabledFor(Level.ERROR)) {
                        logger.error("Received Continue primitive, but state is not proper: " + this.state + ", Dialog: "
                                + this);
                    }
                    this.sendAbnormalDialog();
                    return;
                }
            }

        } finally {
            this.dialogLock.unlock();
        }
    }

    protected void processResponse(TCResponseMessage msg, SccpAddress localAddress, SccpAddress remoteAddress) {
        TCResponseIndicationImpl tcEndIndication = null;
        try {
            this.dialogLock.lock();

            try {
                if (this.previewMode) {
                    tcEndIndication = (TCResponseIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
                            .getDialogPrimitiveFactory()).createResponseIndication(this);

                    DialogPortion dialogPortion = msg.getDialogPortion();
                    if (dialogPortion != null) {
//                        DialogAPDU apdu = dialogPortion.getDialogAPDU();
//                        if (apdu.getType() == DialogAPDUType.Response) {
//                            DialogResponseAPDU responseAPDU = (DialogResponseAPDU) apdu;
//                            // this will be present if APDU is present.
//                            if (!responseAPDU.getApplicationContextName().equals(this.lastACN)) {
//                                this.lastACN = responseAPDU.getApplicationContextName();
//                            }
//                            if (responseAPDU.getUserInformation() != null) {
//                                this.lastUI = responseAPDU.getUserInformation();
//                            }
//                            tcEndIndication.setApplicationContextName(responseAPDU.getApplicationContextName());
//                            tcEndIndication.setUserInformation(responseAPDU.getUserInformation());
//                        }
                    }
                    // now comps
                    tcEndIndication.setComponents(processOperationsState(msg.getComponent()));

                    this.provider.deliver(this, tcEndIndication);
                } else {
                    restartIdleTimer();
                    tcEndIndication = (TCResponseIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
                            .getDialogPrimitiveFactory()).createResponseIndication(this);

                    DialogPortion dialogPortion = msg.getDialogPortion();
                    if (dialogPortion != null) {
//                        DialogAPDU apdu = dialogPortion.getDialogAPDU();
//                        if (apdu.getType() != DialogAPDUType.Response) {
//                            if (logger.isEnabledFor(Level.ERROR)) {
//                                logger.error("Received non-Response APDU: " + apdu.getType() + ". Dialog: " + this);
//                            }
//                            // we do not send "this.sendAbnormalDialog()"
//                            // because no sense to send an answer to TC-END
//                            return;
//                        }
//                        DialogResponseAPDU responseAPDU = (DialogResponseAPDU) apdu;
                        // this will be present if APDU is present.
//                        if (!responseAPDU.getApplicationContextName().equals(this.lastACN)) {
//                            this.lastACN = responseAPDU.getApplicationContextName();
//                        }
//                        if (responseAPDU.getUserInformation() != null) {
//                            this.lastUI = responseAPDU.getUserInformation();
//                        }
//                        tcEndIndication.setApplicationContextName(responseAPDU.getApplicationContextName());
//                        tcEndIndication.setUserInformation(responseAPDU.getUserInformation());

                    }
                    // now comps
                    tcEndIndication.setComponents(processOperationsState(msg.getComponent()));

                    this.provider.deliver(this, tcEndIndication);
                }
            } finally {
                release();
            }
        } finally {
            this.dialogLock.unlock();
        }
    }

    protected void processAbort(TCAbortMessage msg, SccpAddress localAddress2, SccpAddress remoteAddress2) {

        try {
            this.dialogLock.lock();

            try {
                Boolean IsAareApdu = false;
                Boolean IsAbrtApdu = false;
                ApplicationContext acn = null;
//                Result result = null;
//                ResultSourceDiagnostic resultSourceDiagnostic = null;
//                AbortSource abrtSrc = null;
                UserInformation userInfo = null;
                DialogPortion dp = msg.getDialogPortion();
                if (dp != null) {
//                    DialogAPDU apdu = dp.getDialogAPDU();
//                    if (apdu != null && apdu.getType() == DialogAPDUType.Abort) {
//                        IsAbrtApdu = true;
//                        DialogAbortAPDU abortApdu = (DialogAbortAPDU) apdu;
//                        abrtSrc = abortApdu.getAbortSource();
//                        userInfo = abortApdu.getUserInformation();
//                    }
//                    if (apdu != null && apdu.getType() == DialogAPDUType.Response) {
//                        IsAareApdu = true;
//                        DialogResponseAPDU resptApdu = (DialogResponseAPDU) apdu;
//                        acn = resptApdu.getApplicationContextName();
//                        result = resptApdu.getResult();
//                        resultSourceDiagnostic = resptApdu.getResultSourceDiagnostic();
//                        userInfo = resptApdu.getUserInformation();
//                    }
                }

                PAbortCause type = msg.getPAbortCause();
                if (type == null) {
//                    if ((abrtSrc != null && abrtSrc.getAbortSourceType() == AbortSourceType.Provider)) {
//                        type = PAbortCause.AbnormalDialogue;
//                    }
//                    if ((resultSourceDiagnostic != null && resultSourceDiagnostic.getDialogServiceProviderType() != null)) {
//                        if (resultSourceDiagnostic.getDialogServiceProviderType() == DialogServiceProviderType.NoCommonDialogPortion)
//                            type = PAbortCause.NoCommonDialoguePortion;
//                        else
//                            type = PAbortCause.NoReasonGiven;
//                    }
                }

                if (type != null) {

                    // its TC-P-Abort
                    TCPAbortIndicationImpl tcAbortIndication = (TCPAbortIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
                            .getDialogPrimitiveFactory()).createPAbortIndication(this);
                    tcAbortIndication.setPAbortCause(type);

                    this.provider.deliver(this, tcAbortIndication);

                } else {
                    // its TC-U-Abort
                    TCUserAbortIndicationImpl tcUAbortIndication = (TCUserAbortIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
                            .getDialogPrimitiveFactory()).createUAbortIndication(this);
//                    if (IsAareApdu)
//                        tcUAbortIndication.SetAareApdu();
//                    if (IsAbrtApdu)
//                        tcUAbortIndication.SetAbrtApdu();
                    tcUAbortIndication.setUserInformation(userInfo);
//                    tcUAbortIndication.setAbortSource(abrtSrc);
                    tcUAbortIndication.setApplicationContextName(acn);
//                    tcUAbortIndication.setResultSourceDiagnostic(resultSourceDiagnostic);

                    this.provider.deliver(this, tcUAbortIndication);
                }
            } finally {
                release();
            }

        } finally {
            this.dialogLock.unlock();
        }
    }

    protected void sendAbnormalDialog() {

        if (this.previewMode)
            return;

        TCPAbortIndicationImpl tcAbortIndication = null;
        try {
            this.dialogLock.lock();

            try {
                // sending to the remote side
                DialogPortion dp = TcapFactory.createDialogPortion();
//                dp.setUnidirectional(false);

//                DialogAbortAPDU dapdu = TcapFactory.createDialogAPDUAbort();
//
//                AbortSource as = TcapFactory.createAbortSource();
//                as.setAbortSourceType(AbortSourceType.Provider);

//                dapdu.setAbortSource(as);
//                dp.setDialogAPDU(dapdu);

                TCAbortMessageImpl msg = (TCAbortMessageImpl) TcapFactory.createTCAbortMessage();
                msg.setDestinationTransactionId(this.remoteTransactionId);
                msg.setDialogPortion(dp);

                AsnOutputStream aos = new AsnOutputStream();
                try {
                    msg.encode(aos);
                    this.provider.send(aos.toByteArray(), false, this.remoteAddress, this.localAddress, this.seqControl);
                } catch (Exception e) {
                    if (logger.isEnabledFor(Level.ERROR)) {
                        logger.error("Failed to send message: ", e);
                    }
                }

                // sending to the local side
                tcAbortIndication = (TCPAbortIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
                        .getDialogPrimitiveFactory()).createPAbortIndication(this);
//                tcAbortIndication.setPAbortCause(PAbortCause.AbnormalDialogue);
                // tcAbortIndication.setLocalProviderOriginated(true);

                this.provider.deliver(this, tcAbortIndication);
            } finally {
                this.release();
                // this.scheduledComponentList.clear();
            }
        } finally {
            this.dialogLock.unlock();
        }
    }

    // protected void sendProviderAbort(PAbortCauseType pAbortCause) {
    //
    // if (this.previewMode)
    // return;
    //
    // TCPAbortIndicationImpl tcAbortIndication = null;
    // try {
    // this.dialogLock.lock();
    //
    // try {
    // // sending to the remote side
    // this.provider.sendProviderAbort(pAbortCause, this.remoteTransactionId, this.remoteAddress,
    // this.localAddress, this.seqControl);
    //
    // // sending to the local side
    // tcAbortIndication = (TCPAbortIndicationImpl) ((DialogPrimitiveFactoryImpl) this.provider
    // .getDialogPrimitiveFactory()).createPAbortIndication(this);
    // tcAbortIndication.setPAbortCause(pAbortCause);
    // tcAbortIndication.setLocalProviderOriginated(true);
    //
    // this.provider.deliver(this, tcAbortIndication);
    // } finally {
    // this.release();
    // // this.scheduledComponentList.clear();
    // }
    // } finally {
    // this.dialogLock.unlock();
    // }
    // }

    protected Component[] processOperationsState(Component[] components) {
        if (components == null) {
            return null;
        }

        List<Component> resultingIndications = new ArrayList<Component>();
        for (Component ci : components) {
            Long invokeId;
            invokeId = ci.getCorrelationId();
            InvokeImpl invoke = null;
            int index = 0;
            if (invokeId != null) {
                index = getIndexFromInvokeId(invokeId);
                invoke = this.operationsSent[index];
            }

            switch (ci.getType()) {

            case Invoke:
                if (invokeId != null && invoke == null) {
                    logger.error(String.format("Rx : %s but no sent Invoke for correlationId exists", ci));
                    this.addReject(resultingIndications, ((InvokeImpl) ci).getInvokeId(), RejectProblem.invokeUnrecognisedCorrelationId);
                } else {
                    if (invoke != null) {
                        ((InvokeImpl) ci).setCorrelationInvoke(invoke);
                    }

                    if (this.previewMode) {
                        resultingIndications.add(ci);
                        index = getIndexFromInvokeId(ci.getCorrelationId());
                        this.operationsSentA[index] = (InvokeImpl) ci;
                        ((InvokeImpl) ci).setDialog(this);
                        ((InvokeImpl) ci).setState(OperationState.Sent);
                    } else {
                        if (!this.addIncomingInvokeId(((InvokeImpl) ci).getInvokeId())) {
                            logger.error(String.format("Rx : %s but there is already Invoke with this invokeId", ci));
                            this.addReject(resultingIndications, ((InvokeImpl) ci).getInvokeId(), RejectProblem.invokeDuplicateInvocation);
                        } else {
                            resultingIndications.add(ci);
                        }
                    }
                }
                break;
                // .................................


            case ReturnResultNotLast:

                if (invoke == null) {
                    logger.error(String.format("Rx : %s but there is no corresponding Invoke", ci));

//                    Problem p = new ProblemImpl();
//                    p.setReturnResultProblemType(ReturnResultProblemType.UnrecognizedInvokeID);
//                    this.addReject(resultingIndications, ci.getInvokeId(), p);
                } else if (invoke.getInvokeClass() != InvokeClass.Class1 && invoke.getInvokeClass() != InvokeClass.Class3) {
                    logger.error(String.format("Rx : %s but Invoke class is not 1 or 3", ci));

//                    Problem p = new ProblemImpl();
//                    p.setReturnResultProblemType(ReturnResultProblemType.ReturnResultUnexpected);
//                    this.addReject(resultingIndications, ci.getInvokeId(), p);
                } else {
                    resultingIndications.add(ci);
                    ReturnResultNotLastImpl rri = (ReturnResultNotLastImpl) ci;
//                    if (rri.getOperationCode() == null)
//                        rri.setOperationCode(invoke.getOperationCode());
                }
                break;

            case ReturnResultLast:

                if (invoke == null) {
                    logger.error(String.format("Rx : %s but there is no corresponding Invoke", ci));

//                    Problem p = new ProblemImpl();
//                    p.setType(ProblemType.ReturnResultNotLast);
//                    p.setReturnResultProblemType(ReturnResultProblemType.UnrecognizedInvokeID);
//                    this.addReject(resultingIndications, ci.getInvokeId(), p);
                } else if (invoke.getInvokeClass() != InvokeClass.Class1 && invoke.getInvokeClass() != InvokeClass.Class3) {
                    logger.error(String.format("Rx : %s but Invoke class is not 1 or 3", ci));

//                    Problem p = new ProblemImpl();
//                    p.setReturnResultProblemType(ReturnResultProblemType.ReturnResultUnexpected);
//                    this.addReject(resultingIndications, ci.getInvokeId(), p);
                } else {
                    invoke.onReturnResultLast();
                    if (invoke.isSuccessReported()) {
                        resultingIndications.add(ci);
                    }
                    ReturnResultLastImpl rri = (ReturnResultLastImpl) ci;
//                    if (rri.getOperationCode() == null)
//                        rri.setOperationCode(invoke.getOperationCode());
                }
                break;

            case ReturnError:
                if (invoke == null) {
                    logger.error(String.format("Rx : %s but there is no corresponding Invoke", ci));

//                    Problem p = new ProblemImpl();
//                    p.setReturnErrorProblemType(ReturnErrorProblemType.UnrecognizedInvokeID);
//                    this.addReject(resultingIndications, ci.getInvokeId(), p);
                } else if (invoke.getInvokeClass() != InvokeClass.Class1 && invoke.getInvokeClass() != InvokeClass.Class2) {
                    logger.error(String.format("Rx : %s but Invoke class is not 1 or 2", ci));

//                    Problem p = new ProblemImpl();
//                    p.setReturnErrorProblemType(ReturnErrorProblemType.ReturnErrorUnexpected);
//                    this.addReject(resultingIndications, ci.getInvokeId(), p);
                } else {
                    invoke.onError();
                    if (invoke.isErrorReported()) {
                        resultingIndications.add(ci);
                    }
                }
                break;

            case Reject:
                Reject rej = (Reject) ci;
                if (invoke != null) {
                    // If the Reject Problem is the InvokeProblemType we
                    // should move the invoke to the idle state
                    RejectProblem problem = rej.getProblem();
//                    if (!rej.isLocalOriginated() && problem.getInvokeProblemType() != null)
//                        invoke.onReject();
                }
                if (rej.isLocalOriginated() && this.isStructured()) {
                    try {
                        // this is a local originated Reject - we are rejecting
                        // an incoming component
                        // we need to send a Reject also to a peer
                        this.sendComponent(rej);
                    } catch (TCAPSendException e) {
                        logger.error("TCAPSendException when sending Reject component : Dialog: " + this, e);
                    }
                }
                resultingIndications.add(ci);
                break;

            default:
                resultingIndications.add(ci);
                break;
            }

        }

        components = new Component[resultingIndications.size()];
        components = resultingIndications.toArray(components);
        return components;

    }

    private void addReject(List<Component> resultingIndications, Long invokeId, RejectProblem p) {
        try {
            Reject rej = TcapFactory.createComponentReject();
            rej.setLocalOriginated(true);
            rej.setCorrelationId(invokeId);
            rej.setProblem(p);

            resultingIndications.add(rej);

            if (this.isStructured())
                this.sendComponent(rej);
        } catch (TCAPSendException e) {
            logger.error(String.format("Error sending Reject component", e));
        }
    }

    protected void setState(TRPseudoState newState) {
        try {
            this.dialogLock.lock();
            // add checks?
            if (this.state == TRPseudoState.Expunged) {
                return;
            }
            this.state = newState;
            if (newState == TRPseudoState.Expunged) {
                stopIdleTimer();
                provider.release(this);
            }
        } finally {
            this.dialogLock.unlock();
        }

    }

    private void startIdleTimer() {
        if (!this.structured)
            return;
        if (this.previewMode)
            return;

        try {
            this.dialogLock.lock();
            if (this.idleTimerFuture != null) {
                throw new IllegalStateException();
            }

            IdleTimerTask t = new IdleTimerTask();
            t.d = this;
            this.idleTimerFuture = this.executor.schedule(t, this.idleTaskTimeout, TimeUnit.MILLISECONDS);

        } finally {
            this.dialogLock.unlock();
        }
    }

    private void stopIdleTimer() {
        if (!this.structured)
            return;

        try {
            this.dialogLock.lock();
            if (this.idleTimerFuture != null) {
                this.idleTimerFuture.cancel(false);
                this.idleTimerFuture = null;
            }

        } finally {
            this.dialogLock.unlock();
        }
    }

    private void restartIdleTimer() {
        stopIdleTimer();
        startIdleTimer();
    }

    private class IdleTimerTask implements Runnable {
        DialogImpl d;

        public void run() {
            try {
                dialogLock.lock();
                d.idleTimerFuture = null;

                d.idleTimerActionTaken = false;
                d.idleTimerInvoked = true;
                provider.timeout(d);
                // send abort
                if (d.idleTimerActionTaken) {
                    startIdleTimer();
                } else {
                    d.release();
                }

            } finally {
                d.idleTimerInvoked = false;
                dialogLock.unlock();
            }
        }

    }

    // ////////////////////
    // IND like methods //
    // ///////////////////
    public void operationEnded(InvokeImpl tcInvokeRequestImpl) {
        try {
            this.dialogLock.lock();
            // this op died cause of timeout, TC-L-CANCEL!
            int index = getIndexFromInvokeId(tcInvokeRequestImpl.getInvokeId());
            freeInvokeId(tcInvokeRequestImpl.getInvokeId());
            this.operationsSent[index] = null;
            // lets call listener
            // This is done actually with COmponentIndication ....
        } finally {
            this.dialogLock.unlock();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.mobicents.protocols.ss7.tcap.api.tc.dialog.Dialog#operationEnded(
     * org.mobicents.protocols.ss7.tcap.tc.component.TCInvokeRequestImpl)
     */
    public void operationTimedOut(InvokeImpl invoke) {
        // this op died cause of timeout, TC-L-CANCEL!
        try {
            this.dialogLock.lock();
            int index = getIndexFromInvokeId(invoke.getInvokeId());
            freeInvokeId(invoke.getInvokeId());
            this.operationsSent[index] = null;
            // lets call listener

        } finally {
            this.dialogLock.unlock();

        }
        this.provider.operationTimedOut(invoke);
    }

    // TC-TIMER-RESET
    public void resetTimer(Long invokeId) throws TCAPException {
        try {
            this.dialogLock.lock();
            int index = getIndexFromInvokeId(invokeId);
            InvokeImpl invoke = operationsSent[index];
            if (invoke == null) {
                throw new TCAPException("No operation with this ID");
            }
            invoke.startTimer();
        } finally {
            this.dialogLock.unlock();
        }
    }

    public TRPseudoState getState() {
        return this.state;
    }

    public Object getUserObject() {
        return this.userObject;
    }

    public void setUserObject(Object userObject) {
        this.userObject = userObject;
    }

    public boolean getPreviewMode() {
        return this.previewMode;
    }

    public PrevewDialogData getPrevewDialogData() {
        return this.prevewDialogData;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {

        return super.toString() + ": Local[" + this.getLocalDialogId() + "] Remote[" + this.getRemoteDialogId()
                + "], LocalAddress[" + localAddress + "] RemoteAddress[" + this.remoteAddress + "]";
    }
}