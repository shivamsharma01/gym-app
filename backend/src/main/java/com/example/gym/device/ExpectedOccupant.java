package com.example.gym.device;

import com.example.gym.device.domain.DeviceObservedUser;
import com.example.gym.device.domain.DeviceReaderBaseline;
import com.example.gym.device.domain.DeviceReviewItem;

/**
 * What the server last knew to be at one reader id. The gateway writes or removes that id only
 * while the reader still shows this record. A null field is not compared; an empty photo hash
 * means no photo. Door and time section are not part of it.
 */
public record ExpectedOccupant(boolean present, String name, String nameEx, Integer userStatus,
                               String validFrom, String validTo, String authority, String faceSha256) {

    public static final ExpectedOccupant ABSENT = new ExpectedOccupant(false, null, null, null, null, null, null, null);

    /** The record the reader verified last. A baseline without a photo means the reader had none. */
    public static ExpectedOccupant of(DeviceReaderBaseline baseline) {
        return new ExpectedOccupant(true, baseline.getReaderName(), baseline.getReaderNameEx(),
                baseline.getUserStatus(), baseline.getValidFrom(), baseline.getValidTo(),
                baseline.getAuthority(), baseline.getFaceSha256() == null ? "" : baseline.getFaceSha256());
    }

    /** What staff saw on the reader when they decided. */
    public static ExpectedOccupant of(DeviceReviewItem item) {
        if (item.isReaderAbsent()) {
            return ABSENT;
        }
        return new ExpectedOccupant(true, item.getReaderName(), item.getReaderNameEx(), item.getReaderUserStatus(),
                DesiredProjectionService.readerTime(item.getReaderValidFrom()),
                DesiredProjectionService.readerTime(item.getReaderValidTo()),
                item.getReaderAuthority(), item.getReaderFaceSha256());
    }

    /** The reader person staff linked, created a member from, or rejected. */
    public static ExpectedOccupant of(DeviceObservedUser observed) {
        return new ExpectedOccupant(true, observed.getReaderName(), observed.getReaderNameEx(), observed.getUserStatus(),
                DesiredProjectionService.readerTime(observed.getValidFrom()),
                DesiredProjectionService.readerTime(observed.getValidTo()),
                observed.getAuthority(), observed.getFaceSha256());
    }

    /**
     * Someone other than this record: the shown name and the photo both differ, and both photos are
     * known. A name edit alone, or an unknown photo, is not enough.
     */
    public boolean isSomeoneElseThan(String readerName, String readerNameEx, String readerFaceSha256) {
        if (!present || faceSha256 == null || faceSha256.isBlank()
                || readerFaceSha256 == null || readerFaceSha256.isBlank()) {
            return false;
        }
        String reader = ReaderReviewService.shown(readerName, readerNameEx);
        String known = ReaderReviewService.shown(name, nameEx);
        return !reader.equalsIgnoreCase(known) && !readerFaceSha256.equalsIgnoreCase(faceSha256);
    }
}
