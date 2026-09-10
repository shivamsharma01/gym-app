using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class OpenDoorEventForm : Form
    {
        private IntPtr m_LoginID = IntPtr.Zero;
        private IntPtr m_RealLoadID = IntPtr.Zero;
        private int m_Channel = 0;
        private int m_ID = 1;

        private static fAnalyzerDataCallBack m_AnalyzerDataCallBack;

        public OpenDoorEventForm()
        {
            InitializeComponent();
        }

        public OpenDoorEventForm(IntPtr loginid, int channel)
        {
            InitializeComponent();
            if (IntPtr.Zero != loginid)
            {
                m_LoginID = loginid;
            }
            m_Channel = channel;
        }

        private void OpenDoorEventForm_Load(object sender, EventArgs e)
        {
            m_AnalyzerDataCallBack = new fAnalyzerDataCallBack(AnalyzerDataCallBack);
            channel_comboBox.Items.Clear();
            if (m_Channel > 0)
            {
                for (int i = 1; i <= m_Channel; i++)
                {
                    channel_comboBox.Items.Add(i);
                }
                channel_comboBox.SelectedIndex = 0;
            }

            listView_realLoadEvent.Columns.Add("RecordID", 70);
            listView_realLoadEvent.Columns.Add("Status", 120);
            listView_realLoadEvent.Columns.Add("CardNo.", 134);
            listView_realLoadEvent.Columns.Add("SN", 100);
            listView_realLoadEvent.Columns.Add("Time", 150);
            listView_realLoadEvent.Columns.Add("EventInfo", 400);
        }
        private void btn_RealLoad_Click(object sender, EventArgs e)
        {
            if (IntPtr.Zero == m_RealLoadID)
            {
                m_ID = 1;
                m_RealLoadID = NETClient.RealLoadPicture(m_LoginID, 0, (uint)EM_EVENT_IVS_TYPE.ALL, true, m_AnalyzerDataCallBack, m_LoginID, IntPtr.Zero);
                if (IntPtr.Zero == m_RealLoadID)
                {
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                btn_RealLoad.Text = "StopLoadEvent()";
            }
            else
            {
                bool ret = NETClient.StopLoadPic(m_RealLoadID);
                if (!ret)
                {
                    m_RealLoadID = IntPtr.Zero;
                    MessageBox.Show(this, NETClient.GetLastError());
                    return;
                }
                m_RealLoadID = IntPtr.Zero;
                btn_RealLoad.Text = "RealLoadEvent()";
                listView_realLoadEvent.Items.Clear();
                pictureBox_image.Image = null;
                pictureBox_image.Refresh();
                pictureBox_faceimage.Image = null;
                pictureBox_faceimage.Refresh();
                pictureBox_candidateimage.Image = null;
                pictureBox_candidateimage.Refresh();
            }
        }


        /// <summary>
        /// event data callback
        /// 
        /// </summary>
        /// <param name="lAnalyzerHandle">analyzerHandle:RealLoadPicture returns value </param>
        /// <param name="dwEventType">event type,see EM_EVENT_IVS_TYPE </param>
        /// <param name="pEventInfo">event information </param>
        /// <param name="pBuffer">picture buffer </param>
        /// <param name="dwBufSize">picture buffer size </param>
        /// <param name="dwUser">user data from RealLoadPicture function </param>
        /// <param name="nSequence">means status of the same uploaded image, when it is 0, it appears first time.When it is 2, it appears last time or appears once.When it is 1, it will appear again. </param>
        /// <param name="reserved">int nState = (int) reserved means current callback data status;when it is 1, it means current data is real time and current callback data is offline;when it is 2,it means offline data send structure </param>
        /// <returns>reserved </returns>
        private int AnalyzerDataCallBack(IntPtr lAnalyzerHandle, uint dwEventType, IntPtr pEventInfo, IntPtr pBuffer, uint dwBufSize, IntPtr dwUser, int nSequence, IntPtr reserved)
        {
            switch (dwEventType)
            {
                case (uint)EM_EVENT_IVS_TYPE.ACCESS_CTL:     // Access control event 
                    {
                        NET_DEV_EVENT_ACCESS_CTL_INFO info = (NET_DEV_EVENT_ACCESS_CTL_INFO)Marshal.PtrToStructure(pEventInfo, typeof(NET_DEV_EVENT_ACCESS_CTL_INFO));

                        var list_item = new ListViewItem();
                        list_item.Text = (listView_realLoadEvent.Items.Count + 1).ToString();
                        list_item.SubItems.Add(info.szCardNo.ToString());
                        list_item.SubItems.Add(info.szUserID);
                        list_item.SubItems.Add(info.szSN);

                        if (!info.UTC.dwYear.Equals(0) && !info.UTC.dwMonth.Equals(0) && !info.UTC.dwDay.Equals(0))
                        {
                            list_item.SubItems.Add(info.RealUTC.ToString());
                        }
                        else
                        {
                            list_item.SubItems.Add(info.stuFileInfo.stuFileTime.ToString());
                        }

                        StringBuilder infoBuilder = new StringBuilder()
                            .Append("Channel:").Append(info.nChannelID).Append(",")
                            .Append("Method:");
                        switch (info.emOpenMethod)
                        {
                            case EM_ACCESS_DOOROPEN_METHOD.CARD:
                                infoBuilder.Append("Card(),");
                                break;
                            case EM_ACCESS_DOOROPEN_METHOD.FACE_RECOGNITION:
                                infoBuilder.Append("Target recognition(),");
                                break;
                            case EM_ACCESS_DOOROPEN_METHOD.FINGERPRINT:
                                infoBuilder.Append("Fingerprint(ZW),");
                                break;
                            case EM_ACCESS_DOOROPEN_METHOD.REMOTE:
                                infoBuilder.Append("Remote(),");
                                break;
                            default:
                                infoBuilder.Append("Unknown(),");
                                break;
                        }
                        infoBuilder.Append("Status:");
                        if (info.bStatus)
                        {
                            infoBuilder.Append("True()");
                        }
                        else
                        {
                            infoBuilder.Append("False()");
                        }

                        infoBuilder.Append(":");
                        switch (info.emAttendanceState)
                        {
                            case EM_ATTENDANCESTATE.SIGNIN:
                                infoBuilder.Append(",");
                                break;
                            case EM_ATTENDANCESTATE.GOOUT:
                                infoBuilder.Append(",");
                                break;
                            case EM_ATTENDANCESTATE.GOOUT_AND_RETRUN:
                                infoBuilder.Append(",");
                                break;
                            case EM_ATTENDANCESTATE.SIGNOUT:
                                infoBuilder.Append(",");
                                break;
                            case EM_ATTENDANCESTATE.WORK_OVERTIME_SIGNIN:
                                infoBuilder.Append(",");
                                break;
                            case EM_ATTENDANCESTATE.WORK_OVERTIME_SIGNOUT:
                                infoBuilder.Append(",");
                                break;
                            default:
                                infoBuilder.Append("Unknown(),");
                                break;
                        }

                        list_item.SubItems.Add(infoBuilder.ToString());

                        this.BeginInvoke(new Action(() =>
                        {
                            listView_realLoadEvent.BeginUpdate();
                            listView_realLoadEvent.Items.Add(list_item);
                            listView_realLoadEvent.EndUpdate();

                            pictureBox_image.Image = null;
                            pictureBox_image.Refresh();
                            pictureBox_faceimage.Image = null;
                            pictureBox_faceimage.Refresh();
                            pictureBox_candidateimage.Image = null;
                            pictureBox_candidateimage.Refresh();

                            //
                            if (IntPtr.Zero != pBuffer && dwBufSize > 0)
                            {
                                byte[] pic = new byte[dwBufSize];
                                Marshal.Copy(pBuffer, pic, 0, (int)dwBufSize);

                                using (MemoryStream stream = new MemoryStream(pic))
                                {
                                    try
                                    {
                                        Image image = Image.FromStream(stream);
                                        this.pictureBox_image.Image = image;
                                        this.pictureBox_image.Refresh();
                                        this.pictureBox_image.Visible = true;
                                        //SavePicture(pic);
                                    }
                                    catch (Exception e)
                                    {
                                        Console.WriteLine(e);
                                    }
                                }
                            }

                            for (int i = 0; i < info.nImageInfoCount; i++)
                            {
                                // 
                                if (info.stuImageInfo[i].emType == EM_ACCESS_CTL_IMAGE_TYPE.FACE)
                                {
                                    byte[] personFaceInfo = new byte[info.stuImageInfo[i].nLength];
                                    Marshal.Copy(IntPtr.Add(pBuffer, (int)info.stuImageInfo[i].nOffSet), personFaceInfo, 0, (int)info.stuImageInfo[i].nLength);
                                    using (MemoryStream stream = new MemoryStream(personFaceInfo))
                                    {
                                        try // add try catch for catch exception when the stream is not image format,and the stream is from device.
                                        {
                                            Image faceImage = Image.FromStream(stream);
                                            pictureBox_faceimage.Image = faceImage;
                                        }
                                        catch (Exception ex)
                                        {
                                            Console.WriteLine(ex);
                                        }
                                    }
                                }

                                // 
                                if (info.stuImageInfo[i].emType == EM_ACCESS_CTL_IMAGE_TYPE.LOCAL)
                                {
                                    byte[] personFaceInfo = new byte[info.stuImageInfo[i].nLength];
                                    Marshal.Copy(IntPtr.Add(pBuffer, (int)info.stuImageInfo[i].nOffSet), personFaceInfo, 0, (int)info.stuImageInfo[i].nLength);
                                    using (MemoryStream stream = new MemoryStream(personFaceInfo))
                                    {
                                        try // add try catch for catch exception when the stream is not image format,and the stream is from device.
                                        {
                                            Image faceImage = Image.FromStream(stream);
                                            pictureBox_candidateimage.Image = faceImage;
                                        }
                                        catch (Exception ex)
                                        {
                                            Console.WriteLine(ex);
                                        }
                                    }
                                }
                            }
                        }));
                    }
                    break;
                case (uint)EM_EVENT_IVS_TYPE.EVENT_IVS_USERMANAGER_FOR_TWSDK:     // UserInfo upload event ()
                    {
                        NET_DEV_EVENT_USERMANAGER_FOR_TWSDK_INFO info = (NET_DEV_EVENT_USERMANAGER_FOR_TWSDK_INFO)Marshal.PtrToStructure(pEventInfo, typeof(NET_DEV_EVENT_USERMANAGER_FOR_TWSDK_INFO));

                        var list_item = new ListViewItem();
                        list_item.Text = (listView_realLoadEvent.Items.Count + 1).ToString();
                        list_item.SubItems.Add(info.szUserIDEx);
                        list_item.SubItems.Add("");
                        list_item.SubItems.Add(info.szSN);
                        DateTime tmpDateTime = new DateTime((int)info.stuUTC.dwYear, (int)info.stuUTC.dwMonth, (int)info.stuUTC.dwDay,
                            (int)info.stuUTC.dwHour, (int)info.stuUTC.dwMinute, (int)info.stuUTC.dwSecond);
                        DateTime retDateTime = Common.Common.GetZoneTimeByUTCTime(tmpDateTime);

                        list_item.SubItems.Add(retDateTime.ToString());

                        string mesInfo = "Name:" + info.szUserName + "；"
                            + "Type:" + info.nUserType.ToString() + "；"
                            + ":" + info.nUserCount.ToString() + "；"
                            + ":" + info.nImageInfoCount.ToString() + "；"
                            + "ZW:" + info.nFingerCount.ToString() + "；";
                        list_item.SubItems.Add(mesInfo);

                        this.BeginInvoke(new Action(() =>
                        {
                            listView_realLoadEvent.BeginUpdate();
                            listView_realLoadEvent.Items.Add(list_item);
                            listView_realLoadEvent.EndUpdate();

                            pictureBox_image.Image = null;
                            pictureBox_image.Refresh();
                            pictureBox_faceimage.Image = null;
                            pictureBox_faceimage.Refresh();
                            pictureBox_candidateimage.Image = null;
                            pictureBox_candidateimage.Refresh();

                            //
                            if (IntPtr.Zero != pBuffer && dwBufSize > 0)
                            {
                                byte[] pic = new byte[dwBufSize];
                                Marshal.Copy(pBuffer, pic, 0, (int)dwBufSize);

                                using (MemoryStream stream = new MemoryStream(pic))
                                {
                                    try
                                    {
                                        Image image = Image.FromStream(stream);
                                        this.pictureBox_image.Image = image;
                                        this.pictureBox_image.Refresh();
                                        this.pictureBox_image.Visible = true;
                                      //  SavePicture(pic);
                                    }
                                    catch (Exception e)
                                    {
                                        Console.WriteLine(e);
                                    }
                                }
                            }

                            for (int i = 0; i < info.nImageInfoCount; i++)
                            {
                                // 
                                if (info.stuImageInfo[i].emImageType == EM_USERMANAGER_IMAGE_TYPE.EM_USERMANAGER_IMAGE_TYPE_FACE)
                                {
                                    byte[] personFaceInfo = new byte[info.stuImageInfo[i].nLength];
                                    Marshal.Copy(IntPtr.Add(pBuffer, (int)info.stuImageInfo[i].nOffset), personFaceInfo, 0, (int)info.stuImageInfo[i].nLength);
                                    using (MemoryStream stream = new MemoryStream(personFaceInfo))
                                    {
                                        try // add try catch for catch exception when the stream is not image format,and the stream is from device.
                                        {
                                            Image faceImage = Image.FromStream(stream);
                                            pictureBox_faceimage.Image = faceImage;
                                        }
                                        catch (Exception ex)
                                        {
                                            Console.WriteLine(ex);
                                        }
                                    }
                                }

                                // 
                                if (info.stuImageInfo[i].emImageType == EM_USERMANAGER_IMAGE_TYPE.EM_USERMANAGER_IMAGE_TYPE_LOCAL)
                                {
                                    byte[] personFaceInfo = new byte[info.stuImageInfo[i].nLength];
                                    Marshal.Copy(IntPtr.Add(pBuffer, (int)info.stuImageInfo[i].nOffset), personFaceInfo, 0, (int)info.stuImageInfo[i].nLength);
                                    using (MemoryStream stream = new MemoryStream(personFaceInfo))
                                    {
                                        try // add try catch for catch exception when the stream is not image format,and the stream is from device.
                                        {
                                            Image faceImage = Image.FromStream(stream);
                                            pictureBox_candidateimage.Image = faceImage;
                                        }
                                        catch (Exception ex)
                                        {
                                            Console.WriteLine(ex);
                                        }
                                    }
                                }
                            }

                        }));
                    }
                    break;
                case (uint)EM_EVENT_IVS_TYPE.EVENT_IVS_TIMECHANGE_FOR_TWSDK:     // Time Change upload event
                    {
                        NET_DEV_EVENT_TIMECHANGE_FOR_TWSDK_INFO info = (NET_DEV_EVENT_TIMECHANGE_FOR_TWSDK_INFO)Marshal.PtrToStructure(pEventInfo, typeof(NET_DEV_EVENT_TIMECHANGE_FOR_TWSDK_INFO));

                        var list_item = new ListViewItem();
                        list_item.Text = (listView_realLoadEvent.Items.Count + 1).ToString();
                        list_item.SubItems.Add("");
                        list_item.SubItems.Add("");
                        list_item.SubItems.Add(info.szSN);
                        DateTime tmpDateTime = new DateTime((int)info.stuUTC.dwYear, (int)info.stuUTC.dwMonth, (int)info.stuUTC.dwDay,
                            (int)info.stuUTC.dwHour, (int)info.stuUTC.dwMinute, (int)info.stuUTC.dwSecond);
                        DateTime retDateTime = Common.Common.GetZoneTimeByUTCTime(tmpDateTime);
                        list_item.SubItems.Add(retDateTime.ToString());

                        string mesInfo = "channel:" + info.nChannelID.ToString() + "；"
                            + "BeforeModifyTime:" + info.stuBeforeModifyTime.ToString() + "；"
                            + "ModifiedTime:" + info.stuModifiedTime.ToString() + "；";
                        list_item.SubItems.Add(mesInfo);

                        this.BeginInvoke(new Action(() =>
                        {
                            listView_realLoadEvent.BeginUpdate();
                            listView_realLoadEvent.Items.Add(list_item);
                            listView_realLoadEvent.EndUpdate();

                            pictureBox_image.Image = null;
                            pictureBox_image.Refresh();
                            pictureBox_faceimage.Image = null;
                            pictureBox_faceimage.Refresh();
                            pictureBox_candidateimage.Image = null;
                            pictureBox_candidateimage.Refresh();

                            //
                            if (IntPtr.Zero != pBuffer && dwBufSize > 0)
                            {
                                byte[] pic = new byte[dwBufSize];
                                Marshal.Copy(pBuffer, pic, 0, (int)dwBufSize);

                                using (MemoryStream stream = new MemoryStream(pic))
                                {
                                    try
                                    {
                                        Image image = Image.FromStream(stream);
                                        this.pictureBox_image.Image = image;
                                        this.pictureBox_image.Refresh();
                                        this.pictureBox_image.Visible = true;
                                       // SavePicture(pic);
                                    }
                                    catch (Exception e)
                                    {
                                        Console.WriteLine(e);
                                    }
                                }
                            }
                        }));
                    }
                    break;

                case (uint)EM_EVENT_IVS_TYPE.CITIZEN_PICTURE_COMPARE:
                    {
                        NET_DEV_EVENT_CITIZEN_PICTURE_COMPARE_INFO info = (NET_DEV_EVENT_CITIZEN_PICTURE_COMPARE_INFO)Marshal.PtrToStructure(pEventInfo, typeof(NET_DEV_EVENT_CITIZEN_PICTURE_COMPARE_INFO));
                        byte[] m_ImageData = new byte[dwBufSize];
                        Marshal.Copy(pBuffer, m_ImageData, 0, (int)dwBufSize);
                        SavePictureLive(m_ImageData);
                    }
                    break;
                default:
                    Console.WriteLine("Other realLoad event received:" + Enum.GetName(typeof(EM_EVENT_IVS_TYPE), dwEventType));
                    break;
            }

            return 1;
        }

        private void SavePicture(byte[] buffer)
        {
            string path = AppDomain.CurrentDomain.BaseDirectory + "Capture\\";
            if (!Directory.Exists(path))
            {
                Directory.CreateDirectory(path);
            }
            string fileName = DateTime.Now.ToString("HH-mm-ss-ffff") + ".jpg";
            string filePath = path + "\\" + fileName;
            using (FileStream fileStream = new FileStream(filePath, FileMode.OpenOrCreate))
            {
                fileStream.Write(buffer, 0, buffer.Length);
                fileStream.Flush();
                fileStream.Dispose();
            }
        }

        private void SavePictureLive(byte[] buffer)
        {
            string path = AppDomain.CurrentDomain.BaseDirectory + "Capture\\";
            if (!Directory.Exists(path))
            {
                Directory.CreateDirectory(path);
            }
            string fileName = DateTime.Now.ToString("HH-mm-ss-ffff") + ".jpg";
            string filePath = path + "\\" + fileName;
            using (FileStream fileStream = new FileStream(filePath, FileMode.OpenOrCreate))
            {
                fileStream.Write(buffer, 0, buffer.Length);
                fileStream.Flush();
                fileStream.Dispose();
            }
        }

        private void OpenDoorEventForm_FormClosing(object sender, FormClosingEventArgs e)
        {
            try
            {
                if (IntPtr.Zero != m_RealLoadID)
                {
                    NETClient.StopLoadPic(m_RealLoadID);
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine(ex.Message);
            }
        }

        private void button1_Click(object sender, EventArgs e)
        {
            try
            {

                NET_IN_ACCESSCONTROL_CAPTURE_CMD stIn = new NET_IN_ACCESSCONTROL_CAPTURE_CMD();
                stIn.dwSize = (uint)Marshal.SizeOf(stIn);
                stIn.emGathertype = EM_GATHER_TYPE.EM_GATHER_TYPE_FACE;
                stIn.szUserID = "9910";

                NET_OUT_ACCESSCONTROL_CAPTURE_CMD stOut = new NET_OUT_ACCESSCONTROL_CAPTURE_CMD();
                stOut.dwSize = (uint)Marshal.SizeOf(stOut);

                bool ret = NETClient.AccessControlCaptureCmd(m_LoginID, ref stIn, ref stOut, 3000);
                if (ret)
                {
                    //  labelCapturePic.Text = "Capturing ...(......)";
                }
                else
                {
                    //  labelCapturePic.Text = "Start capture failed()";
                }
            }
            catch
            {

            }
        }
    }
}
