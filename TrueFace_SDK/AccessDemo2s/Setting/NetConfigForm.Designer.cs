namespace AccessDemo2s
{
    partial class NetConfigForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.btn_Get = new System.Windows.Forms.Button();
            this.txt_gateway = new System.Windows.Forms.TextBox();
            this.label6 = new System.Windows.Forms.Label();
            this.txt_mask = new System.Windows.Forms.TextBox();
            this.label5 = new System.Windows.Forms.Label();
            this.btn_Set = new System.Windows.Forms.Button();
            this.txt_ip = new System.Windows.Forms.TextBox();
            this.label1 = new System.Windows.Forms.Label();
            this.groupBox2 = new System.Windows.Forms.GroupBox();
            this.chb_enable = new System.Windows.Forms.CheckBox();
            this.label7 = new System.Windows.Forms.Label();
            this.btn_AutoGet = new System.Windows.Forms.Button();
            this.txt_deviceip = new System.Windows.Forms.TextBox();
            this.label2 = new System.Windows.Forms.Label();
            this.txt_port = new System.Windows.Forms.TextBox();
            this.label3 = new System.Windows.Forms.Label();
            this.btn_AutoSet = new System.Windows.Forms.Button();
            this.txt_deviceid = new System.Windows.Forms.TextBox();
            this.label4 = new System.Windows.Forms.Label();
            this.groupBox1.SuspendLayout();
            this.groupBox2.SuspendLayout();
            this.SuspendLayout();
            // 
            // groupBox1
            // 
            this.groupBox1.BackColor = System.Drawing.Color.FromArgb(((int)(((byte)(255)))), ((int)(((byte)(255)))), ((int)(((byte)(192)))));
            this.groupBox1.Controls.Add(this.btn_Get);
            this.groupBox1.Controls.Add(this.txt_gateway);
            this.groupBox1.Controls.Add(this.label6);
            this.groupBox1.Controls.Add(this.txt_mask);
            this.groupBox1.Controls.Add(this.label5);
            this.groupBox1.Controls.Add(this.btn_Set);
            this.groupBox1.Controls.Add(this.txt_ip);
            this.groupBox1.Controls.Add(this.label1);
            this.groupBox1.Location = new System.Drawing.Point(13, 13);
            this.groupBox1.Margin = new System.Windows.Forms.Padding(4);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Padding = new System.Windows.Forms.Padding(4);
            this.groupBox1.Size = new System.Drawing.Size(386, 231);
            this.groupBox1.TabIndex = 7;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "Device Login()";
            // 
            // btn_Get
            // 
            this.btn_Get.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Get.Location = new System.Drawing.Point(67, 186);
            this.btn_Get.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(100, 22);
            this.btn_Get.TabIndex = 17;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // txt_gateway
            // 
            this.txt_gateway.Location = new System.Drawing.Point(219, 125);
            this.txt_gateway.Margin = new System.Windows.Forms.Padding(4);
            this.txt_gateway.Name = "txt_gateway";
            this.txt_gateway.Size = new System.Drawing.Size(120, 23);
            this.txt_gateway.TabIndex = 16;
            // 
            // label6
            // 
            this.label6.AutoSize = true;
            this.label6.Location = new System.Drawing.Point(22, 128);
            this.label6.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label6.Name = "label6";
            this.label6.Size = new System.Drawing.Size(189, 14);
            this.label6.TabIndex = 15;
            this.label6.Text = "Default Gateway():";
            // 
            // txt_mask
            // 
            this.txt_mask.Location = new System.Drawing.Point(219, 81);
            this.txt_mask.Margin = new System.Windows.Forms.Padding(4);
            this.txt_mask.Name = "txt_mask";
            this.txt_mask.Size = new System.Drawing.Size(120, 23);
            this.txt_mask.TabIndex = 14;
            // 
            // label5
            // 
            this.label5.AutoSize = true;
            this.label5.Location = new System.Drawing.Point(50, 84);
            this.label5.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label5.Name = "label5";
            this.label5.Size = new System.Drawing.Size(161, 14);
            this.label5.TabIndex = 13;
            this.label5.Text = "Subnet Mask():";
            // 
            // btn_Set
            // 
            this.btn_Set.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Set.Location = new System.Drawing.Point(219, 186);
            this.btn_Set.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Set.Name = "btn_Set";
            this.btn_Set.Size = new System.Drawing.Size(100, 24);
            this.btn_Set.TabIndex = 12;
            this.btn_Set.Text = "Set()";
            this.btn_Set.UseVisualStyleBackColor = true;
            this.btn_Set.Click += new System.EventHandler(this.btn_Set_Click);
            // 
            // txt_ip
            // 
            this.txt_ip.Location = new System.Drawing.Point(219, 36);
            this.txt_ip.Margin = new System.Windows.Forms.Padding(4);
            this.txt_ip.Name = "txt_ip";
            this.txt_ip.Size = new System.Drawing.Size(120, 23);
            this.txt_ip.TabIndex = 1;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(127, 39);
            this.label1.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(84, 14);
            this.label1.TabIndex = 0;
            this.label1.Text = "IP(IP):";
            // 
            // groupBox2
            // 
            this.groupBox2.BackColor = System.Drawing.Color.FromArgb(((int)(((byte)(192)))), ((int)(((byte)(255)))), ((int)(((byte)(192)))));
            this.groupBox2.Controls.Add(this.chb_enable);
            this.groupBox2.Controls.Add(this.label7);
            this.groupBox2.Controls.Add(this.btn_AutoGet);
            this.groupBox2.Controls.Add(this.txt_deviceip);
            this.groupBox2.Controls.Add(this.label2);
            this.groupBox2.Controls.Add(this.txt_port);
            this.groupBox2.Controls.Add(this.label3);
            this.groupBox2.Controls.Add(this.btn_AutoSet);
            this.groupBox2.Controls.Add(this.txt_deviceid);
            this.groupBox2.Controls.Add(this.label4);
            this.groupBox2.Location = new System.Drawing.Point(13, 252);
            this.groupBox2.Margin = new System.Windows.Forms.Padding(4);
            this.groupBox2.Name = "groupBox2";
            this.groupBox2.Padding = new System.Windows.Forms.Padding(4);
            this.groupBox2.Size = new System.Drawing.Size(386, 248);
            this.groupBox2.TabIndex = 18;
            this.groupBox2.TabStop = false;
            this.groupBox2.Text = "AutoRegisterCfg()";
            // 
            // chb_enable
            // 
            this.chb_enable.AutoSize = true;
            this.chb_enable.Location = new System.Drawing.Point(175, 41);
            this.chb_enable.Name = "chb_enable";
            this.chb_enable.Size = new System.Drawing.Size(15, 14);
            this.chb_enable.TabIndex = 19;
            this.chb_enable.UseVisualStyleBackColor = true;
            // 
            // label7
            // 
            this.label7.AutoSize = true;
            this.label7.Location = new System.Drawing.Point(69, 40);
            this.label7.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label7.Name = "label7";
            this.label7.Size = new System.Drawing.Size(98, 14);
            this.label7.TabIndex = 18;
            this.label7.Text = "Enable():";
            // 
            // btn_AutoGet
            // 
            this.btn_AutoGet.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_AutoGet.Location = new System.Drawing.Point(67, 210);
            this.btn_AutoGet.Margin = new System.Windows.Forms.Padding(4);
            this.btn_AutoGet.Name = "btn_AutoGet";
            this.btn_AutoGet.Size = new System.Drawing.Size(100, 22);
            this.btn_AutoGet.TabIndex = 17;
            this.btn_AutoGet.Text = "Get()";
            this.btn_AutoGet.UseVisualStyleBackColor = true;
            this.btn_AutoGet.Click += new System.EventHandler(this.btn_AutoGet_Click);
            // 
            // txt_deviceip
            // 
            this.txt_deviceip.Location = new System.Drawing.Point(175, 163);
            this.txt_deviceip.Margin = new System.Windows.Forms.Padding(4);
            this.txt_deviceip.Name = "txt_deviceip";
            this.txt_deviceip.Size = new System.Drawing.Size(120, 23);
            this.txt_deviceip.TabIndex = 16;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Location = new System.Drawing.Point(83, 166);
            this.label2.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(84, 14);
            this.label2.TabIndex = 15;
            this.label2.Text = "IP(IP):";
            // 
            // txt_port
            // 
            this.txt_port.Location = new System.Drawing.Point(175, 119);
            this.txt_port.Margin = new System.Windows.Forms.Padding(4);
            this.txt_port.Name = "txt_port";
            this.txt_port.Size = new System.Drawing.Size(120, 23);
            this.txt_port.TabIndex = 14;
            // 
            // label3
            // 
            this.label3.AutoSize = true;
            this.label3.Location = new System.Drawing.Point(83, 122);
            this.label3.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(84, 14);
            this.label3.TabIndex = 13;
            this.label3.Text = "Port():";
            // 
            // btn_AutoSet
            // 
            this.btn_AutoSet.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_AutoSet.Location = new System.Drawing.Point(216, 208);
            this.btn_AutoSet.Margin = new System.Windows.Forms.Padding(4);
            this.btn_AutoSet.Name = "btn_AutoSet";
            this.btn_AutoSet.Size = new System.Drawing.Size(100, 24);
            this.btn_AutoSet.TabIndex = 12;
            this.btn_AutoSet.Text = "Set()";
            this.btn_AutoSet.UseVisualStyleBackColor = true;
            this.btn_AutoSet.Click += new System.EventHandler(this.btn_AutoSet_Click);
            // 
            // txt_deviceid
            // 
            this.txt_deviceid.Location = new System.Drawing.Point(175, 74);
            this.txt_deviceid.Margin = new System.Windows.Forms.Padding(4);
            this.txt_deviceid.Name = "txt_deviceid";
            this.txt_deviceid.Size = new System.Drawing.Size(120, 23);
            this.txt_deviceid.TabIndex = 1;
            // 
            // label4
            // 
            this.label4.AutoSize = true;
            this.label4.Location = new System.Drawing.Point(34, 77);
            this.label4.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(133, 14);
            this.label4.TabIndex = 0;
            this.label4.Text = "Device ID(ID):";
            // 
            // NetConfigForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(412, 513);
            this.Controls.Add(this.groupBox2);
            this.Controls.Add(this.groupBox1);
            this.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "NetConfigForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "NetConfig()";
            this.Load += new System.EventHandler(this.NetConfigForm_Load);
            this.groupBox1.ResumeLayout(false);
            this.groupBox1.PerformLayout();
            this.groupBox2.ResumeLayout(false);
            this.groupBox2.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.Button btn_Set;
        private System.Windows.Forms.TextBox txt_ip;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.Button btn_Get;
        private System.Windows.Forms.TextBox txt_gateway;
        private System.Windows.Forms.Label label6;
        private System.Windows.Forms.TextBox txt_mask;
        private System.Windows.Forms.Label label5;
        private System.Windows.Forms.GroupBox groupBox2;
        private System.Windows.Forms.Button btn_AutoGet;
        private System.Windows.Forms.TextBox txt_deviceip;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.TextBox txt_port;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.Button btn_AutoSet;
        private System.Windows.Forms.TextBox txt_deviceid;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.Label label7;
        private System.Windows.Forms.CheckBox chb_enable;
    }
}