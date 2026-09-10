using NetSDKCS;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace AccessDemo2s
{
    public partial class SpecialDayInfoForm : Form
    {
        public NET_ACCESSCTL_SPECIALDAY_INFO m_SpecialDayInfo = new NET_ACCESSCTL_SPECIALDAY_INFO();

        public SpecialDayInfoForm()
        {
            InitializeComponent();
        }

        public SpecialDayInfoForm(NET_ACCESSCTL_SPECIALDAY_INFO info)
        {
            InitializeComponent();
            m_SpecialDayInfo = info;
        }

        private void SpecialDayInfoForm_Load(object sender, EventArgs e)
        {
            txt_CustomName.Text = m_SpecialDayInfo.szDayName;
            starttime.Value = m_SpecialDayInfo.stuStartTime.ToDateTime();
            endtime.Value = m_SpecialDayInfo.stuEndTime.ToDateTime();
        }

        private void btn_Confirm_Click(object sender, EventArgs e)
        {
            byte[] nameArray = Encoding.Default.GetBytes(txt_CustomName.Text);
            int nameCount = nameArray.Length < 32 ? nameArray.Length : 32;
            byte[] tempArray = new byte[32];
            for (int i = 0; i < nameCount; i++)
            {
                tempArray[i] = nameArray[i];
            }
            m_SpecialDayInfo.szDayName = Encoding.Default.GetString(tempArray);
            m_SpecialDayInfo.stuStartTime = NET_TIME.FromDateTime(starttime.Value);
            m_SpecialDayInfo.stuEndTime = NET_TIME.FromDateTime(endtime.Value);
            DialogResult = DialogResult.OK;
            Close();
        }
    }
}
