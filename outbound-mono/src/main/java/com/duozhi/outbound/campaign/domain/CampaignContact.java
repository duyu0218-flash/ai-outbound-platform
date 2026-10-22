package com.duozhi.outbound.campaign.domain;

/** Source fields and application defaults; no persistence framework state. */
public class CampaignContact {
    private Integer id;
    private Integer campaignId;
    private Integer contactId;
    private Integer contactOrder = 0;
    private Boolean isActive = true;

    public Integer getId() {
        return id;
    }

    public void setId(Integer value) {
        this.id = value;
    }

    public Integer getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(Integer value) {
        this.campaignId = value;
    }

    public Integer getContactId() {
        return contactId;
    }

    public void setContactId(Integer value) {
        this.contactId = value;
    }

    public Integer getContactOrder() {
        return contactOrder;
    }

    public void setContactOrder(Integer value) {
        this.contactOrder = value;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean value) {
        this.isActive = value;
    }
}
