package io.aeron.archive;

import io.aeron.Publication;
import io.aeron.archive.codecs.RecordingDescriptorDecoder;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.io.File;

import static io.aeron.archive.Catalog.wrapDescriptorDecoder;
import static io.aeron.archive.codecs.RecordingDescriptorDecoder.BLOCK_LENGTH;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

public class ListRecordingsForUriSessionTest
{
    private static final int SEGMENT_FILE_SIZE = 128 * 1024 * 1024;
    private final UnsafeBuffer descriptorBuffer = new UnsafeBuffer();
    private final RecordingDescriptorDecoder recordingDescriptorDecoder = new RecordingDescriptorDecoder();
    private long[] matchingRecordingIds = new long[3];
    private final File archiveDir = TestUtil.makeTempDir();
    private final EpochClock clock = mock(EpochClock.class);

    private Catalog catalog;
    private final long correlationId = 1;
    private final Publication controlPublication = mock(Publication.class);
    private final ControlSessionProxy controlSessionProxy = mock(ControlSessionProxy.class);
    private final ControlSession controlSession = mock(ControlSession.class);

    @Before
    public void before() throws Exception
    {
        catalog = new Catalog(archiveDir, null, 0, clock);
        matchingRecordingIds[0] = catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 6, 1, "channel", "channelA?tag=f", "sourceA");
        catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 7, 1, "channelA", "channel?tag=f", "sourceV");
        matchingRecordingIds[1] = catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 8, 1, "channel", "channel?tag=f", "sourceB");
        catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 8, 1, "channelB", "channelB?tag=f", "sourceB");
        matchingRecordingIds[2] = catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 8, 1, "channel", "channel?tag=f", "sourceB");
    }

    @After
    public void after()
    {
        CloseHelper.quietClose(catalog);
        IoUtil.delete(archiveDir, false);
    }

    @Test
    public void shouldSendAllDescriptors()
    {
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            controlPublication,
            0,
            3,
            "channel",
            1,
            catalog,
            controlSessionProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlPublication.maxPayloadLength()).thenReturn(8096);
        session.doWork();
        verify(controlSessionProxy, times(3)).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        verifyNoMoreInteractions(controlSessionProxy);
    }

    @Test
    public void shouldSend2Descriptors()
    {
        final long fromRecordingId = 1;
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            controlPublication,
            fromRecordingId,
            2,
            "channel",
            1,
            catalog,
            controlSessionProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlPublication.maxPayloadLength()).thenReturn(8096);
        final MutableLong counter = new MutableLong(fromRecordingId);
        when(controlSessionProxy.sendDescriptor(eq(correlationId), any(), eq(controlPublication)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSessionProxy, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        verifyNoMoreInteractions(controlSessionProxy);
    }

    @Test
    public void shouldLimitSendingToSingleMtu()
    {
        when(controlPublication.maxPayloadLength()).thenReturn(BLOCK_LENGTH);
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            controlPublication,
            0,
            3,
            "channel",
            1,
            catalog,
            controlSessionProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        final MutableLong counter = new MutableLong(0);
        when(controlSessionProxy.sendDescriptor(eq(correlationId), any(), eq(controlPublication)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSessionProxy).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        session.doWork();
        verify(controlSessionProxy, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        session.doWork();
        verify(controlSessionProxy, times(3)).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        verifyNoMoreInteractions(controlSessionProxy);
    }

    private Answer<Object> verifySendDescriptor(final MutableLong counter)
    {
        return (invocation) ->
        {
            final UnsafeBuffer b = invocation.getArgument(1);
            wrapDescriptorDecoder(recordingDescriptorDecoder, b);

            final int i = counter.intValue();
            assertThat(recordingDescriptorDecoder.recordingId(), is(matchingRecordingIds[i]));
            counter.set(i + 1);
            return b.getInt(0);
        };
    }

    @Test
    public void shouldSend2DescriptorsAndRecordingUnknown()
    {
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            controlPublication,
            1,
            5,
            "channel",
            1,
            catalog,
            controlSessionProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlPublication.maxPayloadLength()).thenReturn(8096);
        session.doWork();
        verify(controlSessionProxy, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        verify(controlSessionProxy).sendRecordingUnknown(eq(correlationId), eq(5L), eq(controlPublication));
        verifyNoMoreInteractions(controlSessionProxy);
    }

    @Test
    public void shouldSendRecordingUnknown()
    {
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            controlPublication,
            1,
            3,
            "notchannel",
            1,
            catalog,
            controlSessionProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlPublication.maxPayloadLength()).thenReturn(8096);
        session.doWork();
        verify(controlSessionProxy, never()).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        verify(controlSessionProxy).sendRecordingUnknown(eq(correlationId), eq(5L), eq(controlPublication));
        verifyNoMoreInteractions(controlSessionProxy);
    }

    @Test
    public void shouldSendUnknownOnFirst()
    {
        when(controlPublication.maxPayloadLength()).thenReturn(4096 - 32);

        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            controlPublication,
            5,
            3,
            "channel",
            1,
            catalog,
            controlSessionProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();

        verify(controlSessionProxy, never()).sendDescriptor(eq(correlationId), any(), eq(controlPublication));
        verify(controlSessionProxy).sendRecordingUnknown(eq(correlationId), eq(5L), eq(controlPublication));
        verifyNoMoreInteractions(controlSessionProxy);
    }
}